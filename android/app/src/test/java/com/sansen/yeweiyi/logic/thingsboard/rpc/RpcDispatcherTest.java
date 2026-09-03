package com.sansen.yeweiyi.logic.thingsboard.rpc;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RpcDispatcherTest {

    private static class InMemoryPersistence implements ProcessedCommandStore.Persistence {
        Map<String, ProcessedCommandStore.Record> saved = new HashMap<>();
        /** 置 true 模拟磁盘满/目录不可写：saveAll 报失败，内容不落盘。 */
        boolean failWrites = false;

        @Override
        public Map<String, ProcessedCommandStore.Record> loadAll() {
            return new HashMap<>(saved);
        }

        @Override
        public boolean saveAll(Map<String, ProcessedCommandStore.Record> records) {
            if (failWrites) {
                return false;
            }
            saved = new HashMap<>(records);
            return true;
        }
    }

    private static class RecordingAckPublisher implements RpcAckPublisher {
        final List<String[]> acks = new ArrayList<>(); // {mqttRequestId, status, errorCode, message}

        @Override
        public void publishAck(String mqttRequestId, RpcResultStatus status, RpcErrorCode errorCode, String message) {
            acks.add(new String[]{mqttRequestId, status.name(), errorCode.name(), message});
        }

        List<RpcResultStatus> statuses() {
            List<RpcResultStatus> result = new ArrayList<>();
            for (String[] ack : acks) {
                result.add(RpcResultStatus.valueOf(ack[1]));
            }
            return result;
        }
    }

    private RpcMethodRegistry registry;
    private InMemoryPersistence persistence;
    private ProcessedCommandStore commandStore;
    private RecordingAckPublisher ackPublisher;
    private long now;
    private RpcDispatcher dispatcher;

    @Before
    public void setUp() {
        registry = new RpcMethodRegistry();
        persistence = new InMemoryPersistence();
        commandStore = new ProcessedCommandStore(persistence, 86400000L);
        ackPublisher = new RecordingAckPublisher();
        now = 1_000_000L;
        dispatcher = new RpcDispatcher(registry, commandStore, ackPublisher, () -> now);
    }

    private static String requestJson(String method, String commandId, long expireAt) {
        return "{\"method\":\"" + method + "\",\"params\":{\"protocolVersion\":\"tb-v1\","
                + "\"commandId\":\"" + commandId + "\",\"expireAt\":" + expireAt + "}}";
    }

    @Test
    public void successfulCommandGetsReceivedThenSucceeded() {
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals(1, calls.get());
        assertEquals(java.util.Arrays.asList(RpcResultStatus.RECEIVED, RpcResultStatus.SUCCEEDED),
                ackPublisher.statuses());
    }

    @Test
    public void unknownMethodIsRejectedWithoutReceivedAck() {
        dispatcher.dispatch("req-1", requestJson("doesNotExist", "cmd-1", now + 10_000));

        assertEquals(Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.UNKNOWN_METHOD, RpcErrorCode.valueOf(ackPublisher.acks.get(0)[2]));
    }

    @Test
    public void expiredCommandIsRejectedBeforeExecuting() {
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now - 1));

        assertEquals(0, calls.get());
        assertEquals(Collections.singletonList(RpcResultStatus.EXPIRED), ackPublisher.statuses());
    }

    @Test
    public void unsupportedProtocolVersionIsRejected() {
        String json = "{\"method\":\"ping\",\"params\":{\"protocolVersion\":\"tb-v2\","
                + "\"commandId\":\"cmd-1\",\"expireAt\":" + (now + 10_000) + "}}";
        registry.register("ping", req -> RpcResult.succeeded("pong", Collections.emptyMap()));

        dispatcher.dispatch("req-1", json);

        assertEquals(Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.PROTOCOL_VERSION_UNSUPPORTED, RpcErrorCode.valueOf(ackPublisher.acks.get(0)[2]));
    }

    @Test
    public void duplicateCommandIdReplaysWithoutReExecuting() {
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));
        // 断线重连后云端用新的 mqttRequestId 重推同一个 commandId。
        dispatcher.dispatch("req-2", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals("handler 只应该真正执行一次", 1, calls.get());
        assertEquals(java.util.Arrays.asList(
                RpcResultStatus.RECEIVED, RpcResultStatus.SUCCEEDED, // 第一次
                RpcResultStatus.SUCCEEDED // 第二次：直接回放终态
        ), ackPublisher.statuses());
        assertEquals("req-2", ackPublisher.acks.get(2)[0]);
    }

    @Test
    public void handlerExceptionBecomesFailedAck() {
        registry.register("boom", req -> {
            throw new RuntimeException("模拟执行异常");
        });

        dispatcher.dispatch("req-1", requestJson("boom", "cmd-1", now + 10_000));

        assertEquals(java.util.Arrays.asList(RpcResultStatus.RECEIVED, RpcResultStatus.FAILED),
                ackPublisher.statuses());
    }

    @Test
    public void malformedPayloadIsRejectedWithoutCrashing() {
        dispatcher.dispatch("req-1", "not json");

        assertEquals(Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.MALFORMED_PARAMS, RpcErrorCode.valueOf(ackPublisher.acks.get(0)[2]));
    }

    @Test
    public void restartRecoveryPreventsReExecutionOfInFlightCommand() {
        // cmd-1 在上次进程里已经进入 RECEIVED（ACK 发出去了）但还没跑完就被杀掉。
        commandStore.record("cmd-1", RpcResultStatus.RECEIVED, RpcErrorCode.NONE, "已受理，执行中", now - 1000);
        commandStore.recoverAfterRestart(now);

        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals("重启前遗留的命令不应该重新执行", 0, calls.get());
        assertEquals(Collections.singletonList(RpcResultStatus.FAILED), ackPublisher.statuses());
    }

    @Test
    public void persistenceFailureAtAcceptanceRefusesToExecute() {
        // 磁盘满/目录不可写：RECEIVED 记录落不了盘，"重启后不重复执行"的保证就没有载体了。
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });
        persistence.failWrites = true;

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals("写盘失败时不能执行 handler", 0, calls.get());
        assertEquals("云端必须拿到一个明确的终态，而不是 RECEIVED 之后没了下文",
                Collections.singletonList(RpcResultStatus.FAILED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.INTERNAL_ERROR, RpcErrorCode.valueOf(ackPublisher.acks.get(0)[2]));
    }

    @Test
    public void persistenceRecoveryLetsNextCommandExecuteAgain() {
        // 磁盘恢复之后不应该留下"这台设备从此不再执行任何命令"的后遗症。
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        persistence.failWrites = true;
        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));
        persistence.failWrites = false;
        dispatcher.dispatch("req-2", requestJson("ping", "cmd-2", now + 10_000));

        assertEquals("换一个 commandId 重下就能正常执行", 1, calls.get());
        assertEquals(java.util.Arrays.asList(
                RpcResultStatus.FAILED,                                 // cmd-1：写盘失败，没执行
                RpcResultStatus.RECEIVED, RpcResultStatus.SUCCEEDED     // cmd-2：磁盘恢复后正常
        ), ackPublisher.statuses());
    }

    @Test
    public void terminalPersistenceFailureStillAcksTheRealResult() {
        // 终态写盘失败跟受理阶段不同：动作已经做完，不能回退成"没执行"，ACK 必须照实发。
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            persistence.failWrites = true; // 执行完、写终态之前磁盘坏掉
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals(1, calls.get());
        assertEquals(java.util.Arrays.asList(RpcResultStatus.RECEIVED, RpcResultStatus.SUCCEEDED),
                ackPublisher.statuses());
    }

    @Test
    public void allHighRiskAndReferenceMethodsAreDiscoverable() {
        registry.register("a", req -> RpcResult.succeeded("ok", Collections.emptyMap()));
        registry.register("b", req -> RpcResult.succeeded("ok", Collections.emptyMap()));
        assertTrue(dispatcher.registeredMethods().keySet().containsAll(java.util.Arrays.asList("a", "b")));
    }
}
