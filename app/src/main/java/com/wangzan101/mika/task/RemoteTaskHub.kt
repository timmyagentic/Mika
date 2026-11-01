package com.wangzan101.mika.task

import android.content.Context
import android.util.Log
import android.os.Build
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.remote.RemoteTaskFiles
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.McpTaskHost
import com.wangzan101.mika.core.mcp.McpTaskLink
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.remote.RemoteTask
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
    private class Record(val id: String, val conversation: String, val goal: String, val createdAtMs: Long) {
        @Volatile var final: TaskState? = null
        @Volatile var steers: List<RemoteSteer> = emptyList()
        @Volatile var files: List<SharedFile> = emptyList()
    }

    override val fileAccess: RemoteTaskFiles? by lazy {
        if ("arm64-v8a" in Build.SUPPORTED_ABIS) PhoneTaskFiles(context, ::exported) else null
    }

    private fun exported(id: String): List<SharedFile> {
        val record = synchronized(this) { records[id] } ?: return emptyList()
        return live(id)?.sharedFiles?.value ?: record.files
    }

    private val buses = HashMap<RunId, RunEventBus>()
    private val records = LinkedHashMap<String, Record>()

    @Synchronized override fun submit(spec: RunSpec): McpTaskLink {
        require(spec.provenance.origin == RunOrigin.Mcp)
        val bus = buses.getOrPut(spec.id) { RunEventBus() }
        val conversation = spec.threadId.takeIf(RemoteTaskService::isConversation) ?: RemoteTaskService.newConversation()
        try { launch(spec.id.value, spec.goal, conversation, spec.provenance.attendance) }
        catch (e: Exception) { buses.remove(spec.id); throw e }
        return McpTaskLink(spec.id, bus) { cancel(spec.id.value) }
    }

    override fun start(goal: String, conversation: String?): StartOutcome {
        val id = UUID.randomUUID().toString()
        val record = try {
            // 电脑那头能接着回答追问,但不一直守着:追问照常等,到时限就当没回答
            launch(id, goal, conversation ?: RemoteTaskService.newConversation(), Attendance.Reachable)
        } catch (e: Exception) {
            Log.w(TAG, "could not start MCP task", e)
            return StartOutcome.Refused("Mika could not start the task on the phone (${e.message ?: e::class.simpleName}). Open Mika on the phone and try again.")
        }
        return StartOutcome.Started(remote(record))
    }

    private fun launch(id: String, goal: String, conversation: String, attendance: Attendance): Record {
        val record = Record(id, conversation, goal, clock())
        synchronized(this) {
            records[id] = record
            while (records.size > KEEP) records.remove(records.keys.first())
        }
        try { TaskService.startMcp(context, goal, conversation, id, attendance) }
        catch (e: Exception) { synchronized(this) { records.remove(id) }; throw e }
        return record
    }

    override fun get(id: String): RemoteTask? = synchronized(this) { records[id] }?.let(::remote)

    override fun list(limit: Int): List<RemoteTask> = synchronized(this) { records.values.toList() }.asReversed().take(limit).map(::remote)

    override fun answer(id: String, text: String): Boolean {
        val run = live(id) ?: return false
        if (run.state.value !is TaskState.WaitingInput) return false
        run.answer(text) {}
        return true
    }

    override fun cancel(id: String): Boolean {
        if (synchronized(this) { records[id] }?.final != null || live(id) == null) return false
        TaskService.cancelMcp(context, id)
        return true
    }

    override fun steer(id: String, text: String): String? {
        val record = synchronized(this) { records[id] } ?: return null
        if (record.final != null) return null
        return live(id)?.steerRemote(text)
    }

    private fun live(id: String): TaskRun? = runs().firstOrNull { it.mcpExternalId == id }

    /** 状态:收尾了用记下的终态,还在跑(或排队)读任务自己的;交出去很久都没登记上的按没开始成算。 */
    private fun remote(r: Record): RemoteTask {
        val run = live(r.id)
        val end = r.final
        val state = end ?: run?.state?.value
            ?: if (clock() - r.createdAtMs > LOST_MS) TaskState.Failed("The task never started on the phone.", FailKind.Environment) else null
        return RemoteTask(r.id, r.conversation, r.goal, r.createdAtMs, state,
            if (end != null) r.steers else run?.let { remoteSteers(it.steers.value) }.orEmpty(),
            (run?.sharedFiles?.value ?: r.files).map { it.remoteFile() })
    }

    @Synchronized fun attach(id: String, source: RunEventBus): AutoCloseable? =
        buses[RunId(id)]?.let { sink -> source.subscribe(sink) }

    /** 任务收尾(含排队时被取消、服务被系统停掉):记下终态,之后状态都从这里读。 */
    @Synchronized fun ended(id: String, end: TaskState, steers: List<SteerNote>, files: List<SharedFile> = emptyList()) {
        records[id]?.let { record ->
            // 由收尾方传快照:排队取消可能已移出中心,服务中断也可能来不及改消息展示状态。
            record.steers = remoteSteers(steers, finished = true)
            record.files = files
            record.final = end
        }
        buses.remove(RunId(id))?.event(RunEvent.State(clock(), "mcp_task_ended", end::class.simpleName.orEmpty()))
    }

    private companion object {
        const val TAG = "MikaMcp"
        /** 只留最近这么多个电脑交来的任务。 */
        const val KEEP = 100
        const val LOST_MS = 2 * 60_000L
    }
}
