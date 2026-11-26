package com.sansen.yeweiyi.logic.thingsboard.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** RpcDispatcher 各路径是否回调到 Listener（真机上 Listener 是 CloudCmdLog，回调即日志）。 */
class RpcDispatcherListenerTest {

    private class Persistence : ProcessedCommandStore.Persistence {
        var saved: Map<String, ProcessedCommandStore.Record> = HashMap()
        var failWrites = false
        override fun loadAll() = HashMap(saved)
        override fun saveAll(records: Map<String, ProcessedCommandStore.Record>): Boolean {
            if (failWrites) return false
            saved = HashMap(records)
            return true
        }
    }

    private class Recorder : RpcDispatcher.Listener {
        val events = ArrayList<String>()
        var executedError: Throwable? = null
        var costMs = -1L

        override fun onMalformed(mqttRequestId: String?, error: String?, payload: String?) {
            events += "malformed"
        }

        override fun onReceived(request: RpcRequestEnvelope) {
            events += "received:${request.commandId}"
        }

        override fun onDuplicate(request: RpcRequestEnvelope, previousStatus: RpcResultStatus?, previousErrorCode: RpcErrorCode?) {
            events += "duplicate:$previousStatus"
        }

        override fun onRejected(request: RpcRequestEnvelope, internalStatus: RpcResultStatus?, errorCode: RpcErrorCode?, message: String?) {
            events += "rejected:$errorCode"
        }

        override fun onExecuted(request: RpcRequestEnvelope, result: RpcResult, error: Throwable?, costMillis: Long) {
            events += "executed:${result.status}"
            executedError = error
            costMs = costMillis
        }

        override fun onTerminalPersistFailed(request: RpcRequestEnvelope, status: RpcResultStatus?) {
            events += "terminalPersistFailed:$status"
        }
    }

    private lateinit var registry: RpcMethodRegistry
    private lateinit var persistence: Persistence
    private lateinit var recorder: Recorder
    private lateinit var dispatcher: RpcDispatcher
    private var now = 1_000_000L

    @Before
    fun setUp() {
        registry = RpcMethodRegistry()
        persistence = Persistence()
        recorder = Recorder()
        dispatcher = RpcDispatcher(registry, ProcessedCommandStore(persistence, 86_400_000L),
            RpcAckPublisher { _, _, _, _, _, _, _ -> },
            RpcResultPublisher { _, _, _, _, _, _ -> },
            RpcDispatcher.Clock { now }, recorder)
    }

    private fun json(method: String, cmd: String, expireAt: Long = now + 10_000) =
        "{\"method\":\"$method\",\"params\":{\"protocolVersion\":1,\"commandId\":\"$cmd\",\"expiresAtMs\":$expireAt}}"

    @Test
    fun successLogsReceivedThenExecutedWithCost() {
        registry.register("ping") { now += 25; RpcResult.succeeded("pong", emptyMap()) }

        dispatcher.dispatch("req-1", json("ping", "cmd-1"))

        assertEquals(listOf("received:cmd-1", "executed:SUCCEEDED"), recorder.events)
        assertNull(recorder.executedError)
        assertEquals(25L, recorder.costMs)
    }

    @Test
    fun handlerExceptionIsPassedToListener() {
        registry.register("boom") { throw IllegalStateException("x") }

        dispatcher.dispatch("req-1", json("boom", "cmd-1"))

        assertEquals(listOf("received:cmd-1", "executed:FAILED"), recorder.events)
        assertNotNull(recorder.executedError)
    }

    @Test
    fun rejectionsBeforeExecutionAreLogged() {
        registry.register("ping") { RpcResult.succeeded("pong", emptyMap()) }

        dispatcher.dispatch("req-1", json("nope", "cmd-1"))
        dispatcher.dispatch("req-2", json("ping", "cmd-2", expireAt = now - 1))
        persistence.failWrites = true
        dispatcher.dispatch("req-3", json("ping", "cmd-3"))

        assertEquals(listOf(
            "received:cmd-1", "rejected:UNKNOWN_METHOD",
            "received:cmd-2", "rejected:EXPIRED",
            "received:cmd-3", "rejected:INTERNAL_ERROR"
        ), recorder.events)
    }

    @Test
    fun malformedAndDuplicateAreLogged() {
        registry.register("ping") { RpcResult.succeeded("pong", emptyMap()) }

        dispatcher.dispatch("req-0", "not json")
        dispatcher.dispatch("req-1", json("ping", "cmd-1"))
        dispatcher.dispatch("req-2", json("ping", "cmd-1"))

        assertEquals(listOf(
            "malformed",
            "received:cmd-1", "executed:SUCCEEDED",
            "received:cmd-1", "duplicate:SUCCEEDED"
        ), recorder.events)
    }

    @Test
    fun terminalPersistFailureIsLogged() {
        registry.register("ping") {
            persistence.failWrites = true
            RpcResult.succeeded("pong", emptyMap())
        }

        dispatcher.dispatch("req-1", json("ping", "cmd-1"))

        assertEquals(listOf("received:cmd-1", "executed:SUCCEEDED", "terminalPersistFailed:SUCCEEDED"), recorder.events)
    }
}
