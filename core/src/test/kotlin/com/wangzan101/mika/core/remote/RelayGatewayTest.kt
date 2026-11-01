package com.wangzan101.mika.core.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RelayGatewayTest {
    @Test fun productionRequiresHttpsOriginWithoutCredentialsOrQuery() {
        assertEquals("https://relay.example/", RelayGateway.parse("https://relay.example").toString())
        for (bad in listOf("http://relay.example", "http://127.0.0.1", "https://user:pass@relay.example", "https://relay.example/path", "https://relay.example?key=secret", "https://relay.example/#fragment")) {
            try { RelayGateway.parse(bad); fail("unsafe address accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun pairingSendsOnlyCodeAndChecksReturnedEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"deviceToken":"${"d".repeat(43)}","mcpUrl":"${server.url("/mcp")}"}"""))
            val result = RelayGateway(server.url("/"), RelayGateway.http()).pair("one-time-code")
            val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("one-time-code", sent.getValue("code").jsonPrimitive.content)
            assertEquals(setOf("code"), sent.keys)
            assertFalse(result.toString().contains(result.deviceToken))
        }
    }

    @Test fun redirectsNeverReceivePairingSecrets() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/pair")))
            try { RelayGateway(server.url("/"), RelayGateway.http()).pair("one-time-code"); fail() }
            catch (_: RelayGateway.Failure) { }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun revokedGatewayCanBeForgottenWithoutClearingAnotherDevice() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(401))
            RelayGateway(server.url("/"), RelayGateway.http()).unpair("old-device-token")
            assertEquals("DELETE", server.takeRequest().method)
        }
    }
}
