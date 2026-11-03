package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 传输层只须实现这两个操作；每个 Run 开局调用一次 tools，之后目录不变。 */
interface McpEndpoint {
    val serverId: String
    fun tools(): List<McpToolDescriptor>
    suspend fun call(name: String, arguments: JsonObject): McpResult

    /** 连接时从服务端读到的资源目录;不支持就是空。 */
    fun resources(): List<McpResourceDescriptor> = emptyList()
    suspend fun readResource(uri: String): McpResult =
        McpResult("MCP server does not support resources", isError = true)

    /** 连接时从服务端读到的提示词模板;不支持就是空。 */
    fun prompts(): List<McpPromptDescriptor> = emptyList()
    suspend fun getPrompt(name: String, arguments: JsonObject): McpResult =
        McpResult("MCP server does not support prompts", isError = true)
}

data class McpAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpAnnotations = McpAnnotations(),
    val estimatedCostUnits: Long = 0,
)

data class McpResult(val text: String, val isError: Boolean = false, val costUnits: Long = 0)

/** resources/list 的一项;v1 只用 uri/name/description 给模型看。 */
data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String = "",
    val mimeType: String? = null,
)

/** prompts/list 的一项。 */
data class McpPromptDescriptor(
    val name: String,
    val description: String = "",
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptArgument(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

/** 每个服务独立配置；Restricted 不采信服务自称的只读注解。 */
enum class McpTrust { Restricted, TrustAnnotations }

/** 长结果的完整文本由宿主保存；返回的引用只含文件路径或资源 ID。 */
fun interface McpResultStore { fun save(serverId: String, toolName: String, content: String): String }

data class McpConnection(
    val endpoint: McpEndpoint,
    val resultStore: McpResultStore? = null,
    val maxCostUnits: Long? = null,
    val previewChars: Int = 4000,
    val trust: McpTrust = McpTrust.Restricted,
    /** 只暴露这些工具;null 表示全部,空集表示一个都不给。 */
    val allowedTools: Set<String>? = null,
) {
    init { require(previewChars > 0 && (maxCostUnits == null || maxCostUnits >= 0)) }
}

/** 没有只读注解就按可写处理；破坏性与开放世界操作记成不可逆、对外(审批记录据此知道任务做过对外的事)。 */
fun mcpApprovalRisk(a: McpAnnotations, trust: McpTrust = McpTrust.TrustAnnotations): ApprovalRisk = when {
    a.destructiveHint == true -> ApprovalRisk(ActionRisk.Irreversible)
    a.openWorldHint == true -> ApprovalRisk(ActionRisk.External)
    a.readOnlyHint == true && trust == McpTrust.TrustAnnotations -> ApprovalRisk(ActionRisk.Read)
    else -> ApprovalRisk(ActionRisk.Change)
}

internal class McpCapability(connection: McpConnection, override val id: String) : Capability {
    private val endpoint = connection.endpoint
    private val budget = McpBudget(connection.maxCostUnits)
    private val serverToolNames = endpoint.tools()
        .filter { connection.allowedTools == null || it.name in connection.allowedTools }
        .map { sanitize(it.name) }
        .toSet()
    private val tools = buildList {
        endpoint.tools()
            .filter { connection.allowedTools == null || it.name in connection.allowedTools }
            .mapTo(this) { McpClientTool(id, endpoint, it, budget, connection.resultStore, connection.previewChars, connection.trust) }
        resourceTool(connection)?.let(::add)
        promptTool(connection)?.let(::add)
    }
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = tools
    override fun sections(env: PromptEnv): List<String> = emptyList()

    /** resources/read 合成一个只读工具;服务端有同名工具时让位给服务端。 */
    private fun resourceTool(connection: McpConnection): AgentTool? {
        val resources = endpoint.resources()
        if (resources.isEmpty() || "resource_read" in serverToolNames) return null
        val available = resources.take(30).joinToString(", ") { it.uri }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = "mcp_${sanitize(endpoint.serverId)}__resource_read",
                description = "Read a resource from the MCP server. Available: $available".take(4000),
                props = mapOf("uri" to buildJsonObject {
                    put("type", "string")
                    put("description", "Resource URI from the list: $available".take(4000))
                }),
                required = listOf("uri"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "resource_read",
            call = { args -> endpoint.readResource(args["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()) },
        )
    }

    /** prompts/get 合成一个只读工具,参数名和说明来自 prompts/list。 */
    private fun promptTool(connection: McpConnection): AgentTool? {
        val prompts = endpoint.prompts()
        if (prompts.isEmpty() || "prompt_get" in serverToolNames) return null
        val names = prompts.take(30).joinToString(", ") { it.name }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = "mcp_${sanitize(endpoint.serverId)}__prompt_get",
                description = "Get a prompt template from the MCP server. Available: $names".take(4000),
                props = mapOf(
                    "name" to buildJsonObject {
                        put("type", "string")
                        put("description", "Prompt name: $names".take(4000))
                    },
                    "arguments" to buildJsonObject {
                        put("type", "object")
                        put("description", "Prompt arguments (see the prompt description on the server)")
                    },
                ),
                required = listOf("name"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "prompt_get",
            call = { args ->
                val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                endpoint.getPrompt(name, args["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
            },
        )
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9_]"), "_")
}

private class McpBudget(private val limit: Long?) {
    private var used = 0L
    @Synchronized fun charge(units: Long): Boolean {
        if (units < 0 || limit != null && units > limit - used) return false
        used += units
        return true
    }
}

private class McpClientTool(
    override val capability: String,
    private val endpoint: McpEndpoint,
    private val descriptor: McpToolDescriptor,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    trust: McpTrust,
) : AgentTool {
    private val schema = descriptor.inputSchema
    private val approval = mcpApprovalRisk(descriptor.annotations, trust)
    override val def: ToolDef = ToolDef(
        "mcp_${endpoint.serverId.replace(Regex("[^A-Za-z0-9_]"), "_")}__${descriptor.name.replace(Regex("[^A-Za-z0-9_]"), "_")}",
        descriptor.description,
        schema["properties"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject },
        schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
    )
    override val verb = Verb.Interact
    override val risk = when (approval.level) {
        ActionRisk.Read -> Risk.Read
        ActionRisk.Change -> Risk.Write
        else -> Risk.External
    }
    override val approvalRisk: ApprovalRisk = approval
    override val offScreen = true
    override val logged = risk != Risk.Read

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        serverId = endpoint.serverId,
        storeName = descriptor.name,
        budget = budget,
        costUnits = descriptor.estimatedCostUnits,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { endpoint.call(descriptor.name, a.o) },
    )
}

/** resources/prompts 合成的只读工具;结果裁剪、预算、不可信标记和普通 MCP 工具共用一套。 */
private class McpSyntheticTool(
    override val capability: String,
    private val serverId: String,
    override val def: ToolDef,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    private val storeName: String,
    private val call: suspend (JsonObject) -> McpResult,
) : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true
    override val logged = false

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        serverId = serverId,
        storeName = storeName,
        budget = budget,
        costUnits = 0,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { call(a.o) },
    )
}

private suspend fun mcpExecution(
    capability: String,
    serverId: String,
    storeName: String,
    budget: McpBudget,
    costUnits: Long,
    resultStore: McpResultStore?,
    previewChars: Int,
    call: suspend () -> McpResult,
): ToolExecution {
    if (!budget.charge(costUnits)) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_cost_limit")
    })
    val result = call()
    val long = result.text.length > previewChars
    val ref = if (long) resultStore?.save(serverId, storeName, result.text) else null
    if (long && ref == null) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_result_too_large")
    })
    val preview = if (long) result.text.take(previewChars) else result.text
    return ToolExecution(buildJsonObject {
        put("ok", !result.isError)
        put("text", Untrusted.wrap(preview))
        ref?.let { put("fullResult", it) }
        if (long) put("truncated", true)
    }, observations = listOf(Observation(ObsKind.Output, preview, source = capability, trust = Trust.Untrusted)))
}
