# Mika 详细 Trace（MVP）

在正式版的「设置 → 开发者选项」打开「记录详细 Trace」，复现一个任务，再从「查看过程 → 详细 Trace」展开未命中量最大的模型调用，查看它与前一次请求的差异。这个流程可以离线完成。OpenTelemetry SDK 生成 trace/span ID，GenAI 语义约定定义调用和用量字段，OTLP/HTTP 提供一个通用的手动发送出口。

我检查的 10 次本地运行里，主循环的 241 次模型回复共记录 21,874,709 个输入 Token，其中 7,767,040 个计为缓存命中，按输入加权为 **35.51%**。最长的一次有 78 步，累计 6,918,005 个输入 Token 未计为命中。已有事件可以定位到步骤，但从一条用量记录看不出当步新加了一张图，还是之前的历史被裁短了。#103 关注导出已有记录，详细 Trace 从启用后的新运行采集请求证据。[运行统计](../../core/src/main/kotlin/com/wangzan101/mika/core/record/RunMetrics.kt) · [已有日志导出提案](https://github.com/wangzan101/Mika/pull/103)

## 记录一条完整的主循环路径

1. **开启与停止。** 在「设置 → 开发者选项」增加默认关闭的「记录详细 Trace」，从下一次运行生效。关掉即停录，已经写入的记录可读，中断或达到容量上限的运行标为「部分记录」。
2. **调用与用量。** 每个运行用一条 OTel trace，根 span 下记录主循环中实际发送的模型请求和顶层工具调用。步号作为属性，同一步的重试分别成 span。保留时间、模型、工具名、工具调用 ID、结果状态和服务商返回的用量。输入、输出、缓存读取沿用 `gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens` 和 `gen_ai.usage.cache_read.input_tokens`。每次用量只计在上报它的请求上，总输入已含缓存读取，显式零与「未上报」分开显示。[GenAI 推理约定](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/client-inference.md)
3. **这一步实际发了什么。** Responses 主循环（含 ChatGPT 登录）记录实际发送的请求。在适配器完成序列化后留下经脱敏的指令、有序工具定义、消息与工具回填文本，图片记录尺寸及内容指纹。用 span ID 对上这次回复和用量。给同模型、同配置的相邻请求记录首个变化块的位置，并关联确实发生的工具展开或历史裁剪。原文采集尚未覆盖的适配器标为「未采集」。[实际请求的组装位置](../../core/src/main/kotlin/com/wangzan101/mika/core/model/ResponsesModel.kt) · [上下文处理与重试](../../core/src/main/kotlin/com/wangzan101/mika/core/agent/ModelCaller.kt)
4. **在手机上排查。** 沿用「查看过程」的步骤列表，展开一次尝试即可看到用量、首处变化和对应文本。按整次运行保存，默认的 .pb 文件只含脱敏 spans。勾选「附带脱敏正文」再保存，会得到包含同一份 Protobuf 和每次请求、可见回复文本的 ZIP。文本以 span ID 命名。鉴权头、Cookie、可识别的凭证与密码字段在写盘前排除。在「开发者选项 → 运行记录」可设置全部私有 Trace 文件的总容量，默认 100 MiB。按文件字节数统计 spans、清单、去重正文与回复，写入需要空间时按归档目录修改时间清理最旧的已结束运行。调低上限会立即清理。录制或导出中的归档受保护，释放后再处理超出的部分。保存到「下载」的副本由用户管理。每次运行正文预算 8 MiB，最多 1,024 个子 span。

**从导出定位缺失。** 根 span 的 `mika.trace.partial_reasons` 汇总部分记录的原因。模型 span 上的 `mika.trace.truncated` 事件给出 `mika.block.path`（如 `input[4]` 或 `response`）和 `mika.truncation.reason`，步号与尝试次数沿用该 span 的属性。单块过长、嵌套过深、单次正文预算用完可分别识别。整库空间用完或达到 span 上限时，根 span 还会留下停止的步号和已知位置。升级后重新导出旧归档时，按原清单恢复已知位置。旧记录无法细分的单块限制标为 `block_limit`，无法还原的原因标为 `unknown`。

## 通用 OTLP/HTTP 的边界

已有接收地址的用户可以手动发送整次运行的脱敏 spans。使用与文件导出相同的 OTLP 消息，以 HTTP POST 发送二进制 Protobuf（`application/x-protobuf`）到指定的 traces endpoint，显示成功、拒收或失败。网络导出使用固定的诊断字段，覆盖调用关系、状态、用量和差异位置。开发和验收只需要一个能够解码标准请求的本地测试接收端。[OTLP/HTTP 规范](https://opentelemetry.io/docs/specs/otlp/)

## 验证方式

- 用固定输入和固定工具返回分别在开关关闭、打开时跑同一任务，检查服务端收到的模型请求体和工具执行顺序一致。关闭时详细 Trace 写入为零，开启记录后离线查看不会触发网络导出。
- 用两个步骤复现一次历史裁剪和一次失败重试。仅从手机或本地文件就能找到同一步的两次尝试，读到相邻请求的首个变化块，对上服务商的缓存计数，并区分零、未上报和已截断的内容。有限证据解释不了服务端命中变化时保留「原因待查」。
- 用本地测试接收端解码实际的 OTLP/HTTP 请求，核对 trace/span ID、父子关系、用量和导出字段。验证停录后能打开已有记录，取消导出或发送失败时仍可在本机排查。

**后续再做。** Langfuse 等平台接入、Collector 部署、平台专用字段映射、账号管理及后台上传均放到首版之后。跨运行分析面板、完整图片归档和多种适配器的深度取证也可按实际排障需求扩展。缓存策略的改动等取得新的逐次证据后再评估。
