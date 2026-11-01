package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.workspace.SandboxPaths
import java.io.File
import java.util.UUID

/** Only files explicitly exported by this task are visible to the computer. */
data class TaskFile(val id: String, val name: String, val size: Long, val sha256: String? = null)

interface RemoteTaskFiles {
    val uploads: PendingUploads
    fun start(conversation: String, goal: String, files: List<Upload>, launch: (String) -> RemoteTask): RemoteTask
    fun read(taskId: String, file: TaskFile, offset: Long): ByteArray
}

data class Upload(val name: String, val size: Long, val path: File)

/** Incomplete uploads stay outside all conversation workspaces. IDs are single use. */
class PendingUploads(private val directory: File, private val reserveFree: Long = 0, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val upload: Upload, var touched: Long)
    private val entries = mutableMapOf<String, Entry>()

    init {
        directory.mkdirs()
        // This directory belongs exclusively to the process-wide upload store. Old IDs died with the process.
        directory.listFiles()?.forEach { it.delete() }
    }

    @Synchronized fun create(name: String, size: Long): String {
        if (!SandboxPaths.plainName(name) || name.length > 255 || size !in 0..MAX_BYTES)
            invalid("Invalid attachment name or size")
        val expired = now() - EXPIRY_MS
        entries.keys.filter { entries.getValue(it).touched < expired }.forEach(::discard)
        if (entries.size >= 32 || entries.values.sumOf { it.upload.size } + size > MAX_BYTES)
            conflict("Pending attachments exceed the transfer limit")
        if (directory.usableSpace - size < reserveFree) conflict("Not enough free space for this attachment")
        val id = UUID.randomUUID().toString()
        val path = File(directory, id)
        if (!path.createNewFile()) conflict("Could not create upload")
        entries[id] = Entry(Upload(name, size, path), now())
        return id
    }

    @Synchronized fun append(id: String, offset: Long, bytes: ByteArray): Long {
        val entry = lookup(id)
        val upload = entry.upload
        if (bytes.isEmpty() || bytes.size > CHUNK_BYTES) invalid("Invalid attachment chunk size")
        if (offset != upload.path.length() || offset + bytes.size > upload.size)
            conflict("Attachment offset or size changed")
        if (directory.usableSpace - bytes.size < reserveFree) conflict("Not enough free space for this attachment")
        upload.path.appendBytes(bytes)
        entry.touched = now()
        return offset + bytes.size
    }

    @Synchronized fun discard(id: String) { entries.remove(id)?.upload?.path?.delete() }

    fun <T> consume(ids: List<String>, use: (List<Upload>) -> T): T {
        if (ids.size !in 1..MAX_FILES || ids.distinct().size != ids.size) invalid("Invalid attachment selection")
        val files = synchronized(this) {
            val selected = ids.map { lookup(it).upload }
            if (selected.any { !it.path.isFile || it.path.length() != it.size }) conflict("Attachment upload is incomplete")
            ids.forEach(entries::remove)
            selected
        }
        return try { use(files) } finally { files.forEach { it.path.delete() } }
    }

    private fun lookup(id: String): Entry {
        val entry = entries[id]
        if (entry == null || entry.touched < now() - EXPIRY_MS || !entry.upload.path.isFile) {
            discard(id)
            throw RemoteTaskError.General(RemoteTaskError.Code.NOT_FOUND, "Upload not found or expired")
        }
        return entry
    }

    private fun invalid(text: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, text)
    private fun conflict(text: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, text)

    companion object {
        const val MAX_FILES = 9
        const val MAX_BYTES = 200L shl 20
        const val CHUNK_BYTES = 128 * 1024
        const val EXPIRY_MS = 30 * 60_000L
    }
}
