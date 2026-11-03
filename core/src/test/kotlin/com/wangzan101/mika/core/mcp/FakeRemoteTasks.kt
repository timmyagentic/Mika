package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.StartOutcome
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.agent.TaskState

/** 测试用的任务出入口:状态由测试直接改,记下回答和取消。 */
class FakeRemoteTasks : RemoteTasks {
    val tasks = LinkedHashMap<String, RemoteTask>()
    val answers = mutableListOf<Pair<String, String>>()
    val cancelled = mutableListOf<String>()
    val steered = mutableListOf<Pair<String, String>>()
    var refuse: String? = null
    private var next = 0

    override fun start(goal: String, conversation: String?, requestId: String?): StartOutcome {
        refuse?.let { return StartOutcome.Refused(it) }
        val n = ++next
        val t = RemoteTask(requestId ?: "t$n", conversation ?: com.wangzan101.mika.core.remote.RemoteTaskService.newConversation(), goal, 1000L * n, null)
        tasks[t.id] = t
        return StartOutcome.Started(t)
    }

    override fun get(id: String) = tasks[id]
    override fun list(limit: Int) = tasks.values.reversed().take(limit)

    override fun answer(id: String, text: String): Boolean {
        answers += id to text
        return true
    }

    override fun cancel(id: String): Boolean {
        cancelled += id
        return true
    }

    override fun steer(id: String, text: String): String? {
        val task = tasks[id]?.takeIf { it.state is TaskState.Running } ?: return null
        steered += id to text
        val messageId = "s${steered.size}"
        tasks[id] = task.copy(steers = task.steers + com.wangzan101.mika.core.remote.RemoteSteer(messageId, "received"))
        return messageId
    }

    fun set(id: String, state: TaskState?) {
        tasks[id] = tasks.getValue(id).copy(state = state)
    }
}
