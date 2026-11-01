package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class DoubaoTransportTest {
    @Test fun bothAuthenticationGenerationsUseSpeechHeadersAndBinaryFrames() = runBlocking {
        for (legacy in listOf(false, true)) {
            val server = MockWebServer(); val client = OkHttpClient()
            try {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        webSocket.send(asrResponse().toByteString())
                    }
                }))
                val auth = if (legacy) DoubaoCredentials(appId = "test-app", accessKey = "test-access") else DoubaoCredentials(apiKey = "test-api")
                val socket = withTimeout(2_000) { DoubaoTransport(client, server.url("/api/v3/sauc/bigmodel_async").toString()).connect(auth, DoubaoProtocol.CONCURRENT) }
                val request = server.takeRequest()
                assertEquals("volc.seedasr.sauc.concurrent", request.getHeader("X-Api-Resource-Id"))
                UUID.fromString(request.getHeader("X-Api-Request-Id"))
                assertNull(request.getHeader("Authorization"))
                if (legacy) {
                    assertEquals("test-app", request.getHeader("X-Api-App-Key"))
                    assertEquals("test-access", request.getHeader("X-Api-Access-Key"))
                    assertNull(request.getHeader("X-Api-Key"))
                } else {
                    assertEquals("test-api", request.getHeader("X-Api-Key"))
                    assertNull(request.getHeader("X-Api-App-Key")); assertNull(request.getHeader("X-Api-Access-Key"))
                }
                socket.send(DoubaoProtocol.initial())
                assertNull(withTimeout(2_000) { DoubaoProtocol.response(socket.incoming.receive()) }.text)
                socket.close()
            } finally {
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown()
            }
        }
    }

    @Test fun handshakeAuthorizationFailureIsSanitized() = runBlocking {
        val server = MockWebServer(); val client = OkHttpClient()
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("private upstream error must not be displayed"))
            val error = try {
                withTimeout(2_000) { DoubaoTransport(client, server.url("/").toString()).connect(DoubaoCredentials(apiKey = "test-only"), DoubaoProtocol.DURATION) }
                null
            } catch (e: ModelError.Auth) { e }
            assertNotNull(error)
            assertFalse(error!!.message!!.contains("private upstream"))
        } finally {
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown()
        }
    }
}
