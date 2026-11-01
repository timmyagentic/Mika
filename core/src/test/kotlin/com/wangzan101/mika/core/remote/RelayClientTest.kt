package com.wangzan101.mika.core.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RelayClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconnect = Channel<Unit>(Channel.UNLIMITED)
    private var relay: RelayClient? = null
    private val replies = Channel<RelayFrame>(Channel.UNLIMITED)

    @After fun close() { relay?.stop(); scope.cancel(); server.shutdown() }

    private fun socket(): CompletableDeferred<WebSocket> {
        val opened = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", RelayClient.PROTOCOL).withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { replies.trySend(Json.decodeFromString<RelayFrame>(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        return opened
    }

    private fun start(handle: (HttpRequest) -> HttpResponse): RelayClient {
        if (server.port == -1) server.start()
        return RelayClient(scope, handle, allowLoopbackHttp = true, reconnectDelay = { reconnect.receive() }).also {
            relay = it
            it.start(RelayPairing(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "d".repeat(43)))
        }
    }

    private fun request(id: String = "test_request_123456", path: String = "/mcp", headers: Map<String, String> = mapOf("content-type" to "application/json")) =
        Json.encodeToString(RelayFrame("request", id, "POST", path, headers, Base64.getEncoder().encodeToString("{}".toByteArray())))

    @Test fun forwardsMcpAndDoesNotInventLoopbackPrivileges() = runBlocking {
        val opened = socket()
        val client = start { req ->
            assertFalse(req.remote.isLoopbackAddress)
            assertNull(req.header("authorization"))
            assertEquals("{}", req.body.toString(Charsets.UTF_8))
            HttpResponse.json(200, "{\"ok\":true}")
        }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            ws.send(request())
            val reply = replies.receive()
            assertEquals(200, reply.status)
            assertEquals("{\"ok\":true}", String(Base64.getDecoder().decode(reply.body)))
        }
        assertEquals("Bearer " + "d".repeat(43), server.takeRequest().getHeader("Authorization"))
    }

    @Test fun duplicateRequestIsNeverExecutedTwice() = runBlocking {
        val calls = AtomicInteger(); val opened = socket()
        val client = start { calls.incrementAndGet(); HttpResponse.empty(202) }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            ws.send(request()); replies.receive()
            ws.send(request()); client.state.first { it == RelayState.Unauthorized }
        }
        assertEquals(1, calls.get())
    }

    @Test fun arbitraryPathsAndForwardedSecretsAreRejected() = runBlocking {
        val opened = socket(); val calls = AtomicInteger()
        val client = start { calls.incrementAndGet(); HttpResponse.empty(200) }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            ws.send(request(path = "/admin", headers = mapOf("x-forwarded-for" to "127.0.0.1")))
            client.state.first { it == RelayState.Unauthorized }
        }
        assertEquals(0, calls.get())
    }

    @Test fun cliQueryAndAuthorizationReachPhoneAuthentication() = runBlocking {
        val opened = socket()
        val access = RemoteAccess({ "phone-token" }, loopbackToken = { "usb-token" })
        val client = start { req ->
            assertFalse(req.remote.isLoopbackAddress)
            assertEquals("limit=2", req.query)
            assertEquals("cli", req.header("x-mika-client"))
            access.handle(req) { HttpResponse.empty(200) }
        }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            for ((index, token) in listOf("usb-token", "phone-token").withIndex()) {
                ws.send(Json.encodeToString(RelayFrame("request", "cli_request_12345$index", "GET", "/api/v1/tasks",
                    mapOf("authorization" to "Bearer $token", "x-mika-client" to "cli"), query = "limit=2")))
                assertEquals(if (index == 0) 401 else 200, replies.receive().status)
            }
        }
    }

    @Test fun gatewayCallerKeysCooldownSoOneGuesserCannotLockOutOthers() = runBlocking {
        val opened = socket()
        val access = RemoteAccess({ "phone-token" })
        val sources = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = start { req -> sources += req.source; access.handle(req) { HttpResponse.empty(200) } }
        var n = 0
        suspend fun send(ws: WebSocket, token: String, caller: String): Int {
            ws.send(Json.encodeToString(RelayFrame("request", "caller_request_${n++}_padding", "GET", "/api/v1",
                mapOf("authorization" to "Bearer $token"), client = caller)))
            return replies.receive().status
        }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            repeat(5) { assertEquals(401, send(ws, "guess", "203.0.113.9")) }
            // Locked out even with the right token: letting it through would confirm a correct guess.
            assertEquals(429, send(ws, "phone-token", "203.0.113.9"))
            assertEquals(200, send(ws, "phone-token", "2001:db8:1:2::/64"))
        }
        assertEquals("relay|203.0.113.9", sources.first())
        assertFalse(sources.any { it == "0.0.0.0" || it.startsWith("127.") })
    }

    @Test fun malformedCallerIsRejected() = runBlocking {
        val opened = socket(); val calls = AtomicInteger()
        val client = start { calls.incrementAndGet(); HttpResponse.empty(200) }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            ws.send(Json.encodeToString(RelayFrame("request", "caller_request_bad_1", "GET", "/api/v1", client = "x\nlocalhost")))
            client.state.first { it == RelayState.Unauthorized }
        }
        assertEquals(0, calls.get())
    }

    @Test fun incompatibleGatewayStopsRetryWithUpdateState() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(426))
        val client = start { HttpResponse.empty(200) }
        withTimeout(3_000) { client.state.first { it == RelayState.Incompatible } }
        assertEquals(1, server.requestCount)
    }

    @Test fun oversizeResponseDoesNotRevokeThePairedConnection() = runBlocking {
        val opened = socket(); var calls = 0
        val client = start { if (calls++ == 0) HttpResponse(200, ByteArray(RelayClient.MAX_BODY + 1)) else HttpResponse.empty(200) }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            ws.send(request()); assertEquals(502, replies.receive().status)
            ws.send(request(id = "next_request_123456")); assertEquals(200, replies.receive().status)
            assertEquals(RelayState.Online, client.state.value)
        }
    }

    @Test fun cancellationInterruptsWaitingRequest() = runBlocking {
        val entered = CountDownLatch(1); val interrupted = CountDownLatch(1); val opened = socket()
        val client = start {
            entered.countDown()
            try { CountDownLatch(1).await(); HttpResponse.empty(200) }
            catch (e: InterruptedException) { interrupted.countDown(); throw e }
        }
        withTimeout(3_000) {
            val ws = opened.await(); client.state.first { it == RelayState.Online }
            ws.send(request())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            ws.send(Json.encodeToString(RelayFrame("cancel", "test_request_123456")))
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun revokedDeviceDoesNotContinuouslyRetryCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = start { HttpResponse.empty(200) }
        withTimeout(3_000) { client.state.first { it == RelayState.Unauthorized } }
        assertEquals(1, server.requestCount)
        client.stop(); assertEquals(RelayState.Off, client.state.value)
    }

    @Test fun reconnectDoesNotReplayCompletedRequestsAndStopPreventsReconnect() = runBlocking {
        val first = socket(); val calls = AtomicInteger(); val client = start { calls.incrementAndGet(); HttpResponse.empty(202) }
        withTimeout(3_000) {
            val ws = first.await(); client.state.first { it == RelayState.Online }
            ws.send(request()); replies.receive(); ws.close(1001, null)
            client.state.first { it == RelayState.Offline }
            val second = socket(); client.networkChanged(); second.await(); client.state.first { it == RelayState.Online }
        }
        assertEquals(1, calls.get())
        client.stop(); reconnect.trySend(Unit)
        assertEquals(RelayState.Off, client.state.value)
    }
}
