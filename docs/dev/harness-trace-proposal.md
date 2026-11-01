# 基于 OpenTelemetry 的 Harness 详细 Trace 设计提案

建议在 Mika 正式版增加默认关闭的「记录详细 Trace」开关。用 OpenTelemetry（OTel）记录一次运行中的模型调用、工具执行和上下文变化，在手机的「查看过程」里查看。需要在电脑上分析时，由用户选择记录，通过 OTLP/HTTP 导出脱敏的 spans。Langfuse 作为首个互操作验收后端。

## 为什么要记录到每一次模型请求

我在一台手机上检查了 10 次运行。主循环的 241 次模型回复共记录 21,874,709 个输入 Token，其中 7,767,040 个记为缓存命中。按输入量加权，命中率只有 **35.51%**。最长的一次跑了 78 步，累计有 6,918,005 个输入 Token 未计为命中。运行小结能定位这些长任务，旁路调用和重试另有合计口径。[运行统计](../../core/src/main/kotlin/com/wangzan101/mika/core/record/RunMetrics.kt)

逐步记录中的 `model_reply` 有用量，`compressed` 有时间和折叠步数，但两次实际请求的第一处差异无法从中还原。`prompt_version` 按全部工具都展开的状态计算，同一版本内实际发送的工具列表仍可能改变。希望能从命中下降的那次调用，查到输入是只在末尾增加了一张图，还是较早的历史内容也被改写了。[现有事件](../../core/src/main/kotlin/com/wangzan101/mika/core/record/RunEvent.kt) · [工具展开与提示词版本](../../core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt)

## 一次运行的 span 树

一次 Mika 运行对应一条 OTel trace。由 SDK 生成 trace ID 和 span ID，通过协程上下文保留父子关系。本机运行编号关联到根 span，嵌套的子任务关联到发起它的步骤。资源属性 `service.name` 标识 Mika，`service.version` 记录应用版本。首版以 GenAI 语义约定描述 Agent、推理与工具调用，实现时固定所用的约定版本并据此验收字段映射。[Agent 与工具的语义约定](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-agent-spans.md) · [模型推理的语义约定](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/client-inference.md)

| 运行中发生的事 | OTel 表示 | 用来回答的问题 |
| --- | --- | --- |
| Mika 启动任务 | 根 span，操作为 `invoke_agent` | 这次运行何时开始、以什么结果结束？ |
| 进入一个步骤 | 根 span 下的内部 span，以 `mika.step.index` 记录步号 | 此步涉及哪些请求、重试与工具？ |
| 向服务商实际发出一次模型请求 | 步骤下的推理 span，按协议记录 `gen_ai.operation.name`、`gen_ai.provider.name`、`gen_ai.request.model`。每次重发单独建 span，记录尝试序号、耗时与错误状态 | 哪一次尝试返回了用量？失败后实际重发的内容有何变化？ |
| 执行一个工具 | 同一步骤下的 `execute_tool` span，保留工具名与工具调用 ID | 模型要求做什么，实际执行了什么，哪个结果进入了下一次请求？ |
| 展开工具组、裁短旧结果、折叠历史或替换图片 | 用 `mika.*` 事件记录触发条件、关联步号和改写前后大小，在受影响的推理 span 上用 `mika.context.first_changed_block` 标记首处差异的位置 | 哪次 Harness 处理改变了旧前缀？ |

用量仅记在服务商实际上报它的推理 span 上。总输入、输出及缓存读取分别使用 `gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、`gen_ai.usage.cache_read.input_tokens`，有缓存写入报告时记录 `gen_ai.usage.cache_write.input_tokens`。总输入包含缓存读取。计算命中率时，以缓存读取量之和除以总输入量之和。只纳入两项都有报告的调用，单列未上报的调用数，显式上报的零照记。父步骤和根 span 的汇总由子调用计算，避免重复计数。部分现有适配器将缺失的缓存字段记成零，采集需在解析原始用量时保留这个区别。[Responses 用量解析](../../core/src/main/kotlin/com/wangzan101/mika/core/model/ResponsesModel.kt) · [GenAI 用量字段](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/client-inference.md)

## 手机上的原文与差异

- 在 **设置 → 高级 → 运行诊断** 打开开关，从下一次运行开始采集，运行中显示「详细记录中」。关掉立即停录，已经写入的部分仍可查看。旧运行可通过 #103 提议的入口导出当时保存的事件。[已有运行的日志导出](https://github.com/wangzan101/Mika/pull/103)
- 在模型适配器完成序列化的位置获取实际请求体。给指令、有序工具定义、历史消息、工具结果和图片逐块记录类型、大小与内容指纹，将请求副本、模型可见回复和工具回填证据绑定到对应的 span ID。诊断编号和时间戳只写入遥测数据。[请求组装示例](../../core/src/main/kotlin/com/wangzan101/mika/core/model/ResponsesModel.kt) · [上下文处理与重试](../../core/src/main/kotlin/com/wangzan101/mika/core/agent/ModelCaller.kt)
- 在 **查看过程** 展开一步，可分别查看同一步的多次尝试。「与上一次比较」选用同一运行里模型、服务商配置和用途相同的前一次请求，跳到首个变化块。若旧内容完全相同而命中量仍下降，显示两次请求的时间间隔和服务商原始计数，原因保留为待查。
- 完整请求和图片存入独立的应用私有目录，按运行去重，支持删除并限制单次与总容量。鉴权头、Cookie、带凭证的 URL 和已识别的密码字段在写盘前排除，遮蔽处有标记。中断、达到上限或写入失败时标出缺口，保留已写部分。现有调试和评测路径会递归复制普通运行目录，原文另存可避免被这条路径带出。[运行记录的复制逻辑](../../app/src/main/java/com/wangzan101/mika/record/RunStore.kt)

## OTLP/HTTP 导出与 Langfuse

打开记录开关即可离线查看。用户需要外部分析时，再选择运行和步骤，预览将要发送的字段。首版用标准 OTel exporter，通过 HTTP POST 发送二进制 Protobuf（`application/x-protobuf`）到用户配置的 traces endpoint。导出子集时带上选中 spans、根 span 及必要的祖先，保留原 trace/span ID，以便在本机与接收端核对。远端默认收到调用树、用量和差异位置。模型请求与响应正文、工具实参和图片保存在手机本地。需要分享原文时，通过本地文件导出，在预览后分别选入正文与图片。[OTLP/HTTP 规范](https://opentelemetry.io/docs/specs/otlp/)

首个端到端验收路径为 **Mika → 用户配置的 OTel Collector → Langfuse**。Collector 将 spans 转发至 Langfuse 的 `/api/public/otel/v1/traces`，项目凭据由 Collector 管理。先按标准 GenAI 属性映射。如果 Langfuse 需要额外字段才能正确显示模型调用（generation）或工具调用，可在 Collector 出口补充 `langfuse.*` 属性。OTLP 导出的字段采用白名单，span 名称、事件属性和错误详情也要排除任务原文与鉴权信息。接收端拒收或部分接收时在导出结果中显示，本机记录继续可读。[Langfuse 官方接入与属性映射](https://langfuse.com/integrations/native/opentelemetry)

## 实现后的验收

1. 用固定模型回复、工具返回与画面跑同一脚本。对比开关打开和关闭时服务端实际收到的请求体、工具执行顺序和任务结果。关闭时额外 Trace 写入为零，单独开启本地记录时网络导出为零。
2. 用合成任务走两个步骤，触发一次历史裁剪和一次失败重试。将选中的记录经 OTLP/HTTP 发给 Collector 和 Langfuse，在手机与 Langfuse 核对**同一 trace ID、父子关系、两次尝试、错误以及用量**。由后端定位到命中下降的调用后，凭同一 span ID 在本机打开首处差异。检查远端接收到的内容符合预览，未上报与真实零值可区分。
3. 分别覆盖仅追加图片、展开工具组和连续点击同一按钮。应能区分首次点击未生效、新画面未回填或模型重新选择了相同动作。验证中途停录、进程中断和容量上限能产生可读的部分记录。
4. 之后再用相同的任务、模型和画面对比缓存优化前后，分别看首轮与连续步骤的加权命中率、每个任务未命中的绝对 Token 数、耗时和任务结果。
