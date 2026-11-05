package com.sansen.yeweiyi.logic.thingsboard.rpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** RPC 方法名 -> handler 的注册表。未注册的方法名在 {@link RpcDispatcher} 里统一按 UNKNOWN_METHOD 拒绝。 */
public final class RpcMethodRegistry {

    private final Map<String, RpcMethodHandler> handlers = new LinkedHashMap<>();

    public RpcMethodRegistry register(String method, RpcMethodHandler handler) {
        if (method == null || method.trim().isEmpty()) {
            throw new IllegalArgumentException("method 不能为空");
        }
        if (handlers.containsKey(method)) {
            throw new IllegalStateException("method 重复注册: " + method);
        }
        handlers.put(method, handler);
        return this;
    }

    public RpcMethodHandler find(String method) {
        return handlers.get(method);
    }

    public Map<String, RpcMethodHandler> allHandlers() {
        return Collections.unmodifiableMap(handlers);
    }
}
