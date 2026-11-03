package com.wangzan101.mika.task

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError

/**
 * 收件先于启动服务落盘。只保存电脑发起的任务，进度仍从正在执行的 TaskRun 读取。
 * 盘上的终态和插话去向按 [RemoteTaskRecord] 的格式存，不直接序列化领域类型。
 * 收尾了的和删对话留下的占位按 [KEEP_MS] / [KEEP] 清掉（收件时和打开时各清一次），没收尾的不清；
 * 清掉之后同一 request_id 会被当成新请求，所以去重只在这个窗口里成立。
 */
internal class RemoteTaskReceipts(
    context: Context, name: String = "remote-tasks.db",
    private val clock: () -> Long = System::currentTimeMillis, private val keep: Int = KEEP,
) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE receipts (
            id TEXT PRIMARY KEY NOT NULL, conversation TEXT, goal TEXT, started INTEGER NOT NULL,
            new_chat INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, result TEXT, steers TEXT)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unknown remote task receipt schema $oldVersion -> $newVersion")

    // MikaApp owns one hub per process. A previous process cannot still be dispatching these tasks.
    init {
        val interrupted: TaskState.Terminal = TaskState.Failed("The phone process stopped before this task finished.", FailKind.Interrupted)
        writableDatabase.execSQL("UPDATE receipts SET result=? WHERE result IS NULL AND goal IS NOT NULL",
            arrayOf(RemoteTaskRecord.encode(interrupted)))
        prune(clock())
    }

    /**
     * 只清已收尾的（result 非空）和删对话留下的占位（goal 为空）：超过 [KEEP_MS] 的，以及按收件先后排在最近 [keep] 条收件之外的。
     * 没收尾的还可能在跑，留着；进程重建时它们已在上面标成中断。
     */
    private fun prune(now: Long) {
        writableDatabase.execSQL("""DELETE FROM receipts WHERE (result IS NOT NULL OR goal IS NULL) AND
            (started < ? OR rowid <= (SELECT rowid FROM receipts ORDER BY rowid DESC LIMIT 1 OFFSET ?))""",
            arrayOf<Any>(now - KEEP_MS, keep))
    }

    private fun Cursor.task(): RemoteTask {
        if (isNull(2)) throw RemoteTaskError.General(RemoteTaskError.Code.GONE, "This task's conversation was deleted.")
        return RemoteTask(getString(0), getString(1), getString(2), getLong(3),
            getString(4)?.let(RemoteTaskRecord::decode),
            getString(5)?.let(RemoteTaskRecord::decodeSteers).orEmpty())
    }

    private val columns = "id, conversation, goal, started, result, steers, new_chat"

    fun find(id: String, goal: String? = null, requestedChat: String? = null): RemoteTask? =
        readableDatabase.rawQuery("SELECT $columns FROM receipts WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val task = cursor.task()
            val originalChat = if (cursor.getInt(6) != 0) null else task.conversation
            if (goal != null && (goal != task.goal || requestedChat != originalChat)) {
                throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, "This request_id was already used for a different task.")
            }
            task
        }

    fun recent(limit: Int): List<RemoteTask> = readableDatabase.rawQuery(
        "SELECT $columns FROM receipts WHERE goal IS NOT NULL ORDER BY rowid DESC LIMIT ?", arrayOf(limit.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.task()) } }

    fun insert(task: RemoteTask, newChat: Boolean) {
        writableDatabase.insertOrThrow("receipts", null, ContentValues().apply {
            put("id", task.id); put("conversation", task.conversation); put("goal", task.goal)
            put("started", task.createdAtMs); put("new_chat", newChat)
        })
        prune(clock())
    }

    /** Android 可能在进程重建后才投递一个已排队的 Intent，已中断或已领取的都不能执行。 */
    fun claimStart(id: String): Boolean = writableDatabase.update("receipts", ContentValues().apply { put("claimed", 1) },
        "id=? AND goal IS NOT NULL AND result IS NULL AND claimed=0", arrayOf(id)) == 1

    fun finish(id: String, state: TaskState.Terminal, steers: List<RemoteSteer>, onlyUnclaimed: Boolean = false): Boolean {
        val condition = "id=? AND goal IS NOT NULL AND result IS NULL" + if (onlyUnclaimed) " AND claimed=0" else ""
        return writableDatabase.update("receipts", ContentValues().apply {
            // 远程接口不暴露回答的证据账本。
            val end = if (state is TaskState.Succeeded) state.copy(sources = emptyList()) else state
            put("result", RemoteTaskRecord.encode(end))
            if (steers.isNotEmpty()) put("steers", RemoteTaskRecord.encodeSteers(steers))
        }, condition, arrayOf(id)) == 1
    }

    fun forget(conversation: String) {
        // 留下占用过的编号，迟到的重试不能重新执行已删除的任务；收件时间留着，占位和收尾的记录一样按时间清。
        writableDatabase.execSQL("UPDATE receipts SET conversation=NULL, goal=NULL, result=NULL, steers=NULL, claimed=0, new_chat=0 WHERE conversation=?",
            arrayOf(conversation))
    }

    companion object {
        /** 收尾后的记录和占位留 30 天：电脑丢了回执、隔天再查或重试都还认得这个编号。 */
        const val KEEP_MS = 30L * 24 * 60 * 60_000
        /** 至多留这么多条已收尾的或占位（按收件先后取最近的），远多于电脑一次会补查的任务数。 */
        const val KEEP = 1_000
    }
}
