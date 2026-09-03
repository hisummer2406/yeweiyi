package com.sansen.yeweiyi.logic.thingsboard.rpc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 幂等去重存储：commandId -> 已记录的终态/中间态。
 * <p>
 * 覆盖三个场景：
 * <ul>
 *   <li>重复投递（断线重连后云端重推同一条命令）——命中已有终态，直接回放，不重新执行；</li>
 *   <li>乱序（先收到一条旧命令，再收到一条新命令，两者 commandId 不同）——本类不处理排序，
 *       乱序只体现在 {@code issuedAt}，由调用方（业务 handler）按需自己比较；</li>
 *   <li>进程重启——{@link #recoverAfterRestart} 把重启前遗留的 {@code EXECUTING} 记录改判
 *       为 {@code FAILED}，因为进程死过一次之后已经没有任何办法确认那次执行到底有没有
 *       完成、有没有产生副作用，只能保守判失败，不能假装成功也不能悬而不决。</li>
 * </ul>
 * 持久化通过 {@link Persistence} 接口注入，纯逻辑部分不碰文件/数据库，单测用内存实现即可。
 */
public final class ProcessedCommandStore {

    public interface Persistence {
        Map<String, Record> loadAll();

        /**
         * @return 是否已经**落到盘上**。返回 false 表示这批记录只存在于内存里，进程一旦重启就没了——
         * 调用方据此决定要不要继续做有副作用的事（见 {@link #record}）。实现不要用抛异常表达失败，
         * 这条路径在 MQTT 回调线程上，抛出去会打断整条 RPC 处理流程。
         */
        boolean saveAll(Map<String, Record> records);
    }

    public static final class Record {
        public final RpcResultStatus status;
        public final RpcErrorCode errorCode;
        public final String message;
        public final long recordedAtMillis;

        public Record(RpcResultStatus status, RpcErrorCode errorCode, String message, long recordedAtMillis) {
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
            this.recordedAtMillis = recordedAtMillis;
        }
    }

    private final Persistence persistence;
    private final long retentionMillis;
    private final Map<String, Record> records;

    public ProcessedCommandStore(Persistence persistence, long retentionMillis) {
        this.persistence = persistence;
        this.retentionMillis = retentionMillis;
        this.records = new LinkedHashMap<>(persistence.loadAll());
    }

    public synchronized Record find(String commandId) {
        return records.get(commandId);
    }

    /**
     * 记录一条命令的状态。
     * <p>
     * <b>返回值必须看</b>：写盘失败时记录只进了内存，本进程内的重复投递仍能去重，但设备一旦重启
     * 这条记录就没了，同一个 commandId 会被当成"从没处理过"重新执行一次。对于要真正动设备的
     * 命令，这等于幂等保证失效，所以 {@link RpcDispatcher} 在受理阶段拿到 false 会直接拒绝执行，
     * 而不是"先干了再说"。
     *
     * @return 是否已持久化成功
     */
    public synchronized boolean record(String commandId, RpcResultStatus status, RpcErrorCode errorCode,
                                     String message, long nowMillis) {
        records.put(commandId, new Record(status, errorCode, message, nowMillis));
        return flush();
    }

    /**
     * 启动时调用一次：任何停留在 {@code EXECUTING} 的记录说明进程在上次执行过程中被杀掉，
     * 一律改判为 FAILED。之后同一个 commandId 再次投递会命中这条 FAILED 记录直接回放，
     * 不会被当成"从没处理过"而重新执行一次。
     */
    public synchronized int recoverAfterRestart(long nowMillis) {
        int recovered = 0;
        for (Map.Entry<String, Record> entry : records.entrySet()) {
            RpcResultStatus status = entry.getValue().status;
            if (status == RpcResultStatus.EXECUTING || status == RpcResultStatus.RECEIVED) {
                entry.setValue(new Record(RpcResultStatus.FAILED, RpcErrorCode.INTERNAL_ERROR,
                        "设备进程在上次执行过程中重启，结果未知，按失败处理", nowMillis));
                recovered++;
            }
        }
        if (recovered > 0) {
            // 这里忽略 flush 的成败：改判本身在内存里已经生效，写盘失败最多是下次重启再改判一次，
            // 不会造成"重复执行"这种有副作用的后果，跟 record() 的处境不一样。
            flush();
        }
        return recovered;
    }

    /** 清掉超过保留期的记录，避免这张表无限增长。建议每次 {@link #record} 之后顺带调用。 */
    public synchronized void evictExpired(long nowMillis) {
        records.entrySet().removeIf(e -> nowMillis - e.getValue().recordedAtMillis > retentionMillis);
        // 同 recoverAfterRestart：清理失败只是表暂时没瘦下来，没有副作用，不需要上报调用方。
        flush();
    }

    private boolean flush() {
        return persistence.saveAll(new LinkedHashMap<>(records));
    }
}
