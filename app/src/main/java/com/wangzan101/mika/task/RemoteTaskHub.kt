package com.wangzan101.mika.task

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.McpTaskHost
import com.wangzan101.mika.core.mcp.McpTaskLink
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.StartOutcome
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunSpec
import java.util.UUID

/**
 * 电脑交来的任务的出入口:经 [TaskService] 开始(和对话界面同一条路,来源沿用已有的远程 MCP 权限策略;CLI 与 MCP 都由这里接入),状态读任务自己的([TaskRun.state]),
 * 这里只记电脑给的编号对应哪个对话、原话,以及任务收尾时的终态(任务对象收尾后就不在了)。
 * 只认这里登记过的编号,手机上发起的任务看不到也动不了。
 */
class RemoteTaskHub(
    private val context: Context,
    private val runs: () -> List<TaskRun> = { (context.applicationContext as com.wangzan101.mika.MikaApp).taskState.runs.value },
    private val clock: () -> Long = System::currentTimeMillis,
) : McpTaskHost, RemoteTasks {
    private val receipts by lazy { RemoteTaskReceipts(context) }
    private val buses = HashMap<RunId, RunEventBus>()
    // A failed receipt write must not abort the phone's own turn cleanup. Retry on lookup.
    private val unsaved = mutableMapOf<String, Pair<TaskState.Terminal, List<RemoteSteer>>>()

    @Synchronized override fun submit(spec: RunSpec): McpTaskLink {
        require(spec.provenance.origin == RunOrigin.Mcp)
        val thread = spec.threadId.takeIf(RemoteTaskService::isConversation)
        val bus = buses.getOrPut(spec.id) { RunEventBus() }
        try { launch(spec.id.value, spec.goal, thread, spec.provenance.attendance) }
        catch (e: Exception) { buses.remove(spec.id); throw e }
        return McpTaskLink(spec.id, bus) { cancel(spec.id.value) }
    }

    override fun start(goal: String, conversation: String?, requestId: String?): StartOutcome = try {
        StartOutcome.Started(launch(requestId ?: UUID.randomUUID().toString(), goal, conversation, Attendance.Reachable))
    } catch (e: RemoteTaskError) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not start remote task", e)
        StartOutcome.Refused("Mika could not start the task on the phone. Check its status before trying again.")
    }

    @Synchronized private fun launch(id: String, goal: String, conversation: String?, attendance: Attendance): RemoteTask {
        receipts.find(id, goal, conversation)?.let { return current(it) }
        val task = RemoteTask(id, conversation ?: RemoteTaskService.newConversation(), goal, clock(), null)
        receipts.insert(task, newChat = conversation == null)
        try { TaskService.startMcp(context, goal, task.conversation, id, attendance) }
        catch (e: Exception) {
            receipts.finish(id, TaskState.Failed("Mika could not start the task on the phone.", FailKind.Environment), emptyList())
            throw e
        }
        return task
    }

    fun claimStart(id: String): Boolean = try {
        receipts.claimStart(id)
    } catch (e: SQLiteException) {
        Log.e(TAG, "Could not claim remote task", e)
        ended(id, TaskState.Failed("Mika could not record this task's start. Check phone storage.", FailKind.Environment), emptyList())
        false
    }
    @Synchronized fun forget(conversation: String) {
        val discarded = unsaved.keys.filter { receipts.find(it)?.conversation == conversation }
        receipts.forget(conversation)
        discarded.forEach(unsaved::remove)
    }
    @Synchronized override fun get(id: String): RemoteTask? = receipts.find(id)?.let(::current)
    @Synchronized override fun list(limit: Int): List<RemoteTask> = receipts.recent(limit).map(::current)

    override fun answer(id: String, text: String): Boolean {
        val run = live(id) ?: return false
        if (run.state.value !is TaskState.WaitingInput) return false
        run.answer(text) {}
        return true
    }

    override fun cancel(id: String): Boolean {
        val task = get(id) ?: return false
        if (task.state is TaskState.Terminal || live(id) == null) return false
        TaskService.cancelMcp(context, id)
        return true
    }

    override fun steer(id: String, text: String): String? {
        if (get(id)?.state !is TaskState.Running) return null
        return live(id)?.steerRemote(text)
    }

    private fun live(id: String): TaskRun? = runs().firstOrNull { it.mcpExternalId == id }

    private fun current(task: RemoteTask): RemoteTask {
        unsaved[task.id]?.let { (end, steers) ->
            try { receipts.finish(task.id, end, steers) }
            catch (_: SQLiteException) {
                throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, "The task result could not be saved. Check phone storage and query this ID again.")
            }
            unsaved.remove(task.id)
            return checkNotNull(receipts.find(task.id))
        }
        if (task.state != null) return task
        val run = live(task.id)
        if (run == null && clock() - task.createdAtMs > LOST_MS) {
            val end = TaskState.Failed("The task never started on the phone.", FailKind.Environment)
            if (receipts.finish(task.id, end, emptyList(), onlyUnclaimed = true)) return task.copy(state = end)
        }
        val state = run?.state?.value
        // The loop can emit a provisional end while cleanup still adjusts the result.
        // Only publish a terminal reply once the service has committed it.
        return task.copy(state = if (state is TaskState.Terminal) TaskState.Running(0, "Finishing the task.") else state,
            steers = run?.let { remoteSteers(it.steers.value) }.orEmpty())
    }

    @Synchronized fun attach(id: String, source: RunEventBus): AutoCloseable? =
        buses[RunId(id)]?.let { sink -> source.subscribe(sink) }

    /** 任务收尾(含排队时被取消、服务被系统停掉):记下终态,之后状态都从这里读。 */
    @Synchronized fun ended(id: String, end: TaskState, steers: List<SteerNote>) {
        require(end is TaskState.Terminal)
        val result = unsaved[id] ?: (end to remoteSteers(steers, finished = true))
        try {
            receipts.finish(id, result.first, result.second)
            unsaved.remove(id)
        } catch (e: SQLiteException) {
            unsaved[id] = result
            Log.e(TAG, "Could not commit remote task result", e)
        }
        buses.remove(RunId(id))?.event(RunEvent.State(clock(), "mcp_task_ended", end::class.simpleName.orEmpty()))
    }

    private companion object {
        const val TAG = "MikaMcp"
        const val LOST_MS = 2 * 60_000L
    }
}
