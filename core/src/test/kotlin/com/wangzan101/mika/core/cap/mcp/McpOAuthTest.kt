package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthTest {
    private fun serverUrl(server: MockWebServer) =
        server.url("/mcp").newBuilder().host("127.0.0.1").build()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun discoversAuthServerAndRegistersPublicClient() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(json("""{"resource":"$base/mcp","authorization_servers":["$base"],"scopes_supported":["mcp","read"]}"""))
            server.enqueue(json("""{"authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"}"""))
            server.enqueue(json("""{"client_id":"cid","client_secret":"sec"}"""))

            val oauth = McpOAuth(OkHttpClient())
            val meta = oauth.discover(serverUrl(server))
            assertEquals("$base/authorize", meta.authorizationEndpoint.toString())
            assertEquals("$base/token", meta.tokenEndpoint.toString())
            assertEquals("mcp read", meta.scope)
            val registration = oauth.register(meta, "http://127.0.0.1:7777/auth/callback")
            assertEquals("cid", registration.clientId)
            assertEquals("sec", registration.clientSecret)

            server.takeRequest()
            server.takeRequest()
            val post = server.takeRequest()
            assertEquals("/register", post.path)
            val body = post.body.readUtf8()
            assertTrue(body.contains("\"token_endpoint_auth_method\":\"none\""))
            assertTrue(body.contains("http://127.0.0.1:7777/auth/callback"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun authorizeUrlCarriesPkceStateAndResource() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            val oauth = McpOAuth(OkHttpClient())
            val meta = McpOAuthServer(
                authorizationEndpoint = "$base/authorize".toUrl(),
                tokenEndpoint = "$base/token".toUrl(),
                registrationEndpoint = "$base/register".toUrl(),
                resource = serverUrl(server),
                scope = "mcp",
            )
            val attempt = oauth.newAttempt("http://127.0.0.1:7777/auth/callback")
            val url = oauth.authorizeUrl(meta, McpOAuthRegistration("cid", null), attempt, meta.resource)
            assertTrue(url.contains("response_type=code"))
            assertTrue(url.contains("client_id=cid"))
            assertTrue(url.contains("code_challenge_method=S256"))
            assertTrue(url.contains("state=" + attempt.state))
            assertTrue(url.contains("code_challenge=" + attempt.challenge))
            assertTrue(url.contains("resource=http%3A%2F%2F127.0.0.1"))
            assertTrue(url.contains("scope=mcp"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun exchangesCodeAndRefreshesWithoutLosingRefreshToken() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(json("""{"access_token":"at1","refresh_token":"rt1","expires_in":3600,"token_type":"Bearer"}"""))
            server.enqueue(json("""{"access_token":"at2","expires_in":3600}"""))
            val oauth = McpOAuth(OkHttpClient())
            val meta = McpOAuthServer(
                authorizationEndpoint = "$base/authorize".toUrl(),
                tokenEndpoint = "$base/token".toUrl(),
                registrationEndpoint = null,
                resource = serverUrl(server),
            )
            val attempt = oauth.newAttempt("http://127.0.0.1:7777/auth/callback")
            val tokens = oauth.exchange(meta, McpOAuthRegistration("cid", null), "code1", attempt, meta.resource)
            assertEquals("at1", tokens.accessToken)
            assertEquals("rt1", tokens.refreshToken)
            assertTrue(tokens.isFresh())
            val first = server.takeRequest().body.readUtf8()
            assertTrue(first.contains("grant_type=authorization_code"))
            assertTrue(first.contains("code_verifier=" + attempt.verifier))

            val refreshed = oauth.refresh(tokens)
            assertEquals("at2", refreshed.accessToken)
            assertEquals("rt1", refreshed.refreshToken)
            val second = server.takeRequest().body.readUtf8()
            assertTrue(second.contains("grant_type=refresh_token"))
            assertTrue(second.contains("refresh_token=rt1"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun tokenErrorDoesNotLeakResponseBody() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(
                json("""{"error":"invalid_grant","error_description":"code expired: secret-detail"}""")
                    .setResponseCode(400),
            )
            val oauth = McpOAuth(OkHttpClient())
            val meta = McpOAuthServer("$base/authorize".toUrl(), "$base/token".toUrl(), null, null)
            val error = runCatching {
                oauth.exchange(meta, McpOAuthRegistration("cid", null), "bad", oauth.newAttempt("http://127.0.0.1:1/cb"), null)
            }.exceptionOrNull()
            assertTrue(error is McpOAuthException)
            assertTrue(error!!.message!!.contains("invalid_grant"))
            assertFalse(error.message!!.contains("secret-detail"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun discoversFromUnauthorizedChallenge() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Bearer resource_metadata=\"$base/prm\""),
            )
            server.enqueue(json("""{"resource":"$base/mcp","authorization_servers":["$base"]}"""))
            server.enqueue(json("""{"authorization_endpoint":"$base/authorize","token_endpoint":"$base/token"}"""))
            val meta = McpOAuth(OkHttpClient()).discover(serverUrl(server))
            assertEquals("$base/authorize", meta.authorizationEndpoint.toString())
        } finally {
            server.shutdown()
        }
    }

    @Test fun rejectsInsecureAuthEndpoint() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(json("""{"authorization_servers":["$base"]}"""))
            server.enqueue(json("""{"authorization_endpoint":"http://example.com/authorize","token_endpoint":"$base/token"}"""))
            val error = runCatching { McpOAuth(OkHttpClient()).discover(serverUrl(server)) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is McpOAuthException)
        } finally {
            server.shutdown()
        }
    }

    private fun String.toUrl() = toHttpUrl()
}
