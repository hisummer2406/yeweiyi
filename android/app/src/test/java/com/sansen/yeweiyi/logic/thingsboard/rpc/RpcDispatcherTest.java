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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    /** 记录一次 ACK：{mqttRequestId, commandId, status(wire), errorCode(name 或 null), message, duplicate}. */
    private static class Ack {
        final String mqttRequestId;
        final String commandId;
        final RpcResultStatus status;
        final RpcErrorCode errorCode;
        final String message;
        final boolean duplicate;

        Ack(String mqttRequestId, String commandId, RpcResultStatus status, RpcErrorCode errorCode, String message,
            boolean duplicate) {
            this.mqttRequestId = mqttRequestId;
            this.commandId = commandId;
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
            this.duplicate = duplicate;
        }
    }

    private static class RecordingAckPublisher implements RpcAckPublisher {
        final List<Ack> acks = new ArrayList<>();

        @Override
        public void publishAck(String mqttRequestId, String commandId, RpcResultStatus status,
                                RpcErrorCode errorCode, String message, boolean duplicate, long tsMillis) {
            acks.add(new Ack(mqttRequestId, commandId, status, errorCode, message, duplicate));
        }

        List<RpcResultStatus> statuses() {
            List<RpcResultStatus> result = new ArrayList<>();
            for (Ack ack : acks) {
                result.add(ack.status);
            }
            return result;
        }
    }

    /** 记录一次异步 cmdResult：{commandId, status, errorCode, message}. */
    private static class Result {
        final String commandId;
        final RpcResultStatus status;
        final RpcErrorCode errorCode;
        final String message;

        Result(String commandId, RpcResultStatus status, RpcErrorCode errorCode, String message) {
            this.commandId = commandId;
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
        }
    }

    private static class RecordingResultPublisher implements RpcResultPublisher {
        final List<Result> results = new ArrayList<>();

        @Override
        public void publishResult(String commandId, RpcResultStatus status, RpcErrorCode errorCode, String message,
                                   Map<String, Object> data, long tsMillis) {
            results.add(new Result(commandId, status, errorCode, message));
        }

        List<RpcResultStatus> statuses() {
            List<RpcResultStatus> result = new ArrayList<>();
            for (Result r : results) {
                result.add(r.status);
            }
            return result;
        }
    }

    private RpcMethodRegistry registry;
    private InMemoryPersistence persistence;
    private ProcessedCommandStore commandStore;
    private RecordingAckPublisher ackPublisher;
    private RecordingResultPublisher resultPublisher;
    private long now;
    private RpcDispatcher dispatcher;

    @Before
    public void setUp() {
        registry = new RpcMethodRegistry();
        persistence = new InMemoryPersistence();
        commandStore = new ProcessedCommandStore(persistence, 86400000L);
        ackPublisher = new RecordingAckPublisher();
        resultPublisher = new RecordingResultPublisher();
        now = 1_000_000L;
        dispatcher = new RpcDispatcher(registry, commandStore, ackPublisher, resultPublisher, () -> now);
    }

    private static String requestJson(String method, String commandId, long expireAt) {
        return "{\"method\":\"" + method + "\",\"params\":{\"protocolVersion\":1,"
                + "\"commandId\":\"" + commandId + "\",\"expiresAtMs\":" + expireAt + "}}";
    }

    @Test
    public void successfulCommandGetsReceivedAckThenSucceededResult() {
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals(1, calls.get());
        assertEquals(Collections.singletonList(RpcResultStatus.RECEIVED), ackPublisher.statuses());
        assertEquals("cmd-1", ackPublisher.acks.get(0).commandId);
        assertFalse(ackPublisher.acks.get(0).duplicate);
        assertEquals(Collections.singletonList(RpcResultStatus.SUCCEEDED), resultPublisher.statuses());
        assertEquals("cmd-1", resultPublisher.results.get(0).commandId);
    }

    @Test
    public void unknownMethodIsRejectedWithoutReceivedAck() {
        dispatcher.dispatch("req-1", requestJson("doesNotExist", "cmd-1", now + 10_000));

        assertEquals(Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.UNKNOWN_METHOD, ackPublisher.acks.get(0).errorCode);
        assertTrue("未执行的命令不应该产生 cmdResult", resultPublisher.results.isEmpty());
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
        // ACK topic 只表示 received/rejected——EXPIRED 在 ACK 上收口成 rejected，
        // 具体原因看 errorCode=EXPIRED；内部去重表仍然记 EXPIRED（见下一个测试）。
        assertEquals(Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.EXPIRED, ackPublisher.acks.get(0).errorCode);
        assertTrue(resultPublisher.results.isEmpty());
    }

    @Test
    public void expiredCommandIsRecordedAsExpiredInternallyForDedup() {
        registry.register("ping", req -> RpcResult.succeeded("pong", Collections.emptyMap()));

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now - 1));

        ProcessedCommandStore.Record record = commandStore.find("cmd-1");
        assertEquals(RpcResultStatus.EXPIRED, record.status);
    }

    @Test
    public void unsupportedProtocolVersionIsRejected() {
        String json = "{\"method\":\"ping\",\"params\":{\"protocolVersion\":2,"
                + "\"commandId\":\"cmd-1\",\"expiresAtMs\":" + (now + 10_000) + "}}";
        registry.register("ping", req -> RpcResult.succeeded("pong", Collections.emptyMap()));

        dispatcher.dispatch("req-1", json);

        assertEquals(Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.PROTOCOL_VERSION_UNSUPPORTED, ackPublisher.acks.get(0).errorCode);
    }

    @Test
    public void duplicateCommandIdReplaysViaAckWithoutReExecutingOrNewResult() {
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));
        // 断线重连后云端用新的 mqttRequestId 重推同一个 commandId。
        dispatcher.dispatch("req-2", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals("handler 只应该真正执行一次", 1, calls.get());
        // 第一次：RECEIVED ack + 一次异步 SUCCEEDED result。
        // 第二次（重复投递）：直接在 ACK 上原样回放 SUCCEEDED，duplicate=true，不产生新的 result。
        assertEquals(java.util.Arrays.asList(RpcResultStatus.RECEIVED, RpcResultStatus.SUCCEEDED),
                ackPublisher.statuses());
        assertTrue(ackPublisher.acks.get(1).duplicate);
        assertEquals("req-2", ackPublisher.acks.get(1).mqttRequestId);
        assertEquals("cmd-1", ackPublisher.acks.get(1).commandId);
        assertEquals("重复投递不应该产生第二条 cmdResult", Collections.singletonList(RpcResultStatus.SUCCEEDED),
                resultPublisher.statuses());
    }

    @Test
    public void handlerExceptionBecomesFailedResult() {
        registry.register("boom", req -> {
            throw new RuntimeException("模拟执行异常");
        });

        dispatcher.dispatch("req-1", requestJson("boom", "cmd-1", now + 10_000));

        assertEquals(Collections.singletonList(RpcResultStatus.RECEIVED), ackPublisher.statuses());
        assertEquals(Collections.singletonList(RpcResultStatus.FAILED), resultPublisher.statuses());
    }

    @Test
    public void malformedPayloadIsRejectedWithoutCrashingOrCommandId() {
        dispatcher.dispatch("req-1", "not json");

        assertEquals(Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.MALFORMED_PARAMS, ackPublisher.acks.get(0).errorCode);
        assertNull("连 commandId 都没解析出来，没有可关联的值", ackPublisher.acks.get(0).commandId);
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
        // recoverAfterRestart 已经把 cmd-1 标成 FAILED（终态），这次 dispatch 命中的是
        // "重复投递、回放终态" 分支，走 ACK 而不是新的 cmdResult。
        assertEquals(Collections.singletonList(RpcResultStatus.FAILED), ackPublisher.statuses());
        assertTrue(ackPublisher.acks.get(0).duplicate);
        assertTrue(resultPublisher.results.isEmpty());
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
        assertEquals("受理阶段的失败在 ACK 上收口成 rejected，具体原因看 errorCode",
                Collections.singletonList(RpcResultStatus.REJECTED), ackPublisher.statuses());
        assertEquals(RpcErrorCode.INTERNAL_ERROR, ackPublisher.acks.get(0).errorCode);
        assertTrue(resultPublisher.results.isEmpty());
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
                RpcResultStatus.REJECTED,   // cmd-1：写盘失败，没执行，ACK 收口成 rejected
                RpcResultStatus.RECEIVED    // cmd-2：磁盘恢复后正常受理
        ), ackPublisher.statuses());
        assertEquals(Collections.singletonList(RpcResultStatus.SUCCEEDED), resultPublisher.statuses());
    }

    @Test
    public void terminalPersistenceFailureStillReportsTheRealResult() {
        // 终态写盘失败跟受理阶段不同：动作已经做完，不能回退成"没执行"，结果必须照实上报。
        AtomicInteger calls = new AtomicInteger(0);
        registry.register("ping", req -> {
            calls.incrementAndGet();
            persistence.failWrites = true; // 执行完、写终态之前磁盘坏掉
            return RpcResult.succeeded("pong", Collections.emptyMap());
        });

        dispatcher.dispatch("req-1", requestJson("ping", "cmd-1", now + 10_000));

        assertEquals(1, calls.get());
        assertEquals(Collections.singletonList(RpcResultStatus.RECEIVED), ackPublisher.statuses());
        assertEquals(Collections.singletonList(RpcResultStatus.SUCCEEDED), resultPublisher.statuses());
    }

    @Test
    public void allHighRiskAndReferenceMethodsAreDiscoverable() {
        registry.register("a", req -> RpcResult.succeeded("ok", Collections.emptyMap()));
        registry.register("b", req -> RpcResult.succeeded("ok", Collections.emptyMap()));
        assertTrue(dispatcher.registeredMethods().keySet().containsAll(java.util.Arrays.asList("a", "b")));
    }
}
