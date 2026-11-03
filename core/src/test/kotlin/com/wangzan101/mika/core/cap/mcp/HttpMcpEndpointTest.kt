package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpMcpEndpointTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message\ndata: $body\n\n")
    private fun init(session: String? = null) = json(
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"},"instructions":"read the docs"}}"""
    ).apply { session?.let { setHeader("Mcp-Session-Id", it) } }
    private fun tools(id: Long, vararg names: String) = json(
        """{"jsonrpc":"2.0","id":$id,"result":{"tools":[${names.joinToString(",") { tool(it) }}]}}"""
    )
    private fun tool(name: String) =
        """{"name":"$name","description":"$name tool","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}"""

    @Test fun connectsInitializesAndCalls() = runBlocking {
        server.enqueue(init("s-1"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "search"))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"found"}],"isError":false}}"""))

        val endpoint = HttpMcpEndpoint.connect(
            "demo", server.url("/mcp").toString(), OkHttpClient(),
            headers = mapOf("Authorization" to "Bearer test"), timeoutSec = 5,
        ).getOrThrow()

        assertEquals("demo", endpoint.serverId)
        assertEquals("demo", endpoint.serverName)
        assertEquals("read the docs", endpoint.instructions)
        assertEquals(listOf("search"), endpoint.tools().map { it.name })
        assertTrue(endpoint.tools().single().annotations.readOnlyHint == true)

        val initRequest = server.takeRequest()
        assertEquals("POST", initRequest.method)
        assertTrue(initRequest.body.readUtf8().contains("\"initialize\""))
        assertEquals("Bearer test", initRequest.getHeader("Authorization"))
        assertNull(initRequest.getHeader("MCP-Protocol-Version"))

        val initialized = server.takeRequest()
        assertTrue(initialized.body.readUtf8().contains("notifications/initialized"))

        val listRequest = server.takeRequest()
        assertEquals("2025-03-26", listRequest.getHeader("MCP-Protocol-Version"))
        assertEquals("s-1", listRequest.getHeader("Mcp-Session-Id"))

        val result = endpoint.call("search", buildJsonObject { put("q", "x") })
        assertFalse(result.isError)
        assertEquals("found", result.text)
        val callRequest = server.takeRequest()
        val callBody = callRequest.body.readUtf8()
        assertTrue(callBody.contains("tools/call"))
        assertTrue(callBody.contains("\"q\":\"x\""))
    }

    @Test fun parsesSseResponses() = runBlocking {
        server.enqueue(sse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"sse"}}}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(sse("""{"jsonrpc":"2.0","id":2,"result":{"tools":[${tool("read")}]}}"""))
        server.enqueue(sse("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"ok"}]}}"""))

        val endpoint = HttpMcpEndpoint.connect("sse", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        assertEquals("sse", endpoint.serverName)
        assertEquals(listOf("read"), endpoint.tools().map { it.name })
        assertEquals("ok", endpoint.call("read", buildJsonObject {}).text)
    }

    @Test fun followsToolsListPagination() = runBlocking {
        server.enqueue(init())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[${tool("a")}],"nextCursor":"page-2"}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"tools":[${tool("b")}]}}"""))

        val endpoint = HttpMcpEndpoint.connect("paged", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        assertEquals(listOf("a", "b"), endpoint.tools().map { it.name })
        server.takeRequest() // initialize
        server.takeRequest() // initialized
        val first = server.takeRequest().body.readUtf8()
        assertFalse(first.contains("page-2"))
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("page-2"))
    }

    @Test fun callErrorsBecomeResults() = runBlocking {
        server.enqueue(init())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "fail"))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"boom"}}"""))

        val endpoint = HttpMcpEndpoint.connect("err", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        val result = endpoint.call("fail", buildJsonObject {})
        assertTrue(result.isError)
        assertTrue(result.text.contains("boom"))
    }

    @Test fun initializeHttpFailureIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        val result = HttpMcpEndpoint.connect("bad", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 500"))
    }

    @Test fun readsResourcesAndPromptsWhenAdvertised() = runBlocking {
        server.enqueue(json(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"caps"},""" +
                """"capabilities":{"tools":{},"resources":{},"prompts":{}}}}"""
        ))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "search"))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"resources":[{"uri":"file://a","name":"A","mimeType":"text/plain"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"prompts":[{"name":"summarize","description":"Sum"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"contents":[{"uri":"file://a","text":"hello"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"description":"Sum","messages":[{"role":"user","content":{"type":"text","text":"do it"}}]}}"""))

        val endpoint = HttpMcpEndpoint.connect("caps", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        assertEquals(listOf("file://a"), endpoint.resources().map { it.uri })
        assertEquals(listOf("summarize"), endpoint.prompts().map { it.name })
        assertEquals("hello", endpoint.readResource("file://a").text)
        assertEquals("Sum\nuser: do it", endpoint.getPrompt("summarize", buildJsonObject {}).text)
    }

    @Test fun doesNotFollowRedirects() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(307)
                .setHeader("Location", "https://example.com/steal")
                .setBody("redirect"),
        )
        val result = HttpMcpEndpoint.connect("redirect", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 307"))
    }

    @Test fun closeUsesSessionId() = runBlocking {
        server.enqueue(init("s-2"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "one"))
        server.enqueue(MockResponse().setResponseCode(200))
        val endpoint = HttpMcpEndpoint.connect("close", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        endpoint.close()
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("s-2", delete.getHeader("Mcp-Session-Id"))
    }
}
