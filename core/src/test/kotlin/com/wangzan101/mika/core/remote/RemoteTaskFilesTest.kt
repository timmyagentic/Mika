package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

class RemoteTaskFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun incompleteAndRepeatedReferencesNeverLaunchAPartialTask() {
        val uploads = PendingUploads(temp.newFolder())
        val empty = uploads.create("空.csv", 0)
        val other = uploads.create("图.png", 3)
        assertThrows(RemoteTaskError::class.java) { uploads.append(other, 1, byteArrayOf(1)) }
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(empty, other)) { fail() } }
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(empty, empty)) { fail() } }
        assertEquals(3L, uploads.append(other, 0, byteArrayOf(0, -1, 3)))
        uploads.consume(listOf(empty, other)) {
            assertArrayEquals(byteArrayOf(0, -1, 3), it[1].path.readBytes())
            assertEquals(0, it[0].path.length())
        }
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(other)) { fail() } }
    }

    @Test fun quotasNamesExpiryAndRejectedSubmissionsReleaseTheStagingFiles() {
        var time = 0L
        val dir = temp.newFolder()
        val uploads = PendingUploads(dir) { time }
        assertThrows(RemoteTaskError::class.java) { uploads.create("../escape", 1) }
        val old = uploads.create("old.dat", PendingUploads.MAX_BYTES)
        assertThrows(RemoteTaskError::class.java) { uploads.create("over", 1) }
        time += PendingUploads.EXPIRY_MS + 1
        val fresh = uploads.create("new.csv", 0)
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(old)) { fail() } }
        assertThrows(IllegalStateException::class.java) { uploads.consume(listOf(fresh)) { error("launch refused") } }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun filesRequireAuthenticationAndBelongToOneSpecificRemoteTask() {
        val fake = FakeRemoteTasks()
        val one = (fake.start("first", null) as StartOutcome.Started).task
        val two = (fake.start("next", one.conversation) as StartOutcome.Started).task
        fake.tasks[one.id] = one.copy(files = listOf(TaskFile("csv", "result.csv", 3)))
        val access = object : RemoteTaskFiles {
            override val uploads = PendingUploads(temp.newFolder())
            override fun start(conversation: String, goal: String, files: List<Upload>, launch: (String) -> RemoteTask) = error("unused")
            override fun read(taskId: String, file: TaskFile, offset: Long) = byteArrayOf(1, 2, 3).copyOfRange(offset.toInt(), 3)
        }
        val tasks = object : RemoteTasks by fake { override val fileAccess = access }
        val host = RemoteHost(tasks, { "unit-file-test" }, 0, false, "test")
        fun read(id: String, offset: Int = 0, auth: Boolean = true): HttpResponse = host.handle(HttpRequest("GET",
            "/api/v1/tasks/$id/files/csv", if (auth) mapOf("authorization" to "Bearer unit-file-test") else emptyMap(),
            byteArrayOf(), InetAddress.getLoopbackAddress(), "offset=$offset"))
        assertEquals(401, read(one.id, auth = false).status)
        assertEquals(404, read(two.id).status)
        assertEquals(400, read(one.id, 4).status)
        assertEquals("\"AgM=\"", Json.parseToJsonElement(read(one.id, 1).body.decodeToString()).jsonObject["data"].toString())
    }
}
