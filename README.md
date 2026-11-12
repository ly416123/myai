# 单 Agent Workflow 学习项目

使用 Java 21 实现的学习型 LLM + Tool Calling Agent。核心循环由项目自己编写，不依赖 Spring AI、LangChain 或 LangGraph。模型通过 OpenAI Java SDK 连接 DeepSeek 的兼容接口。

## 环境与运行

- Java 21
- Maven 3.9+
- DeepSeek API Key

将 API Key 写入项目根目录 **deepseek.key**（只放 Key 本身），或设置环境变量：

    export DEEPSEEK_API_KEY="你的 DeepSeek API Key"
    export DEEPSEEK_MODEL="deepseek-flash"
    mvn compile exec:java

环境变量 DEEPSEEK_API_KEY 优先于密钥文件；DEEPSEEK_MODEL 默认是 deepseek-flash。运行记录默认写入 .agent-runs/，可通过 AGENT_RUN_STORE 修改目录。密钥文件和运行目录均已加入 .gitignore。

CLI 命令：

- 输入普通文本：开始一个新 Run。
- /runs：查看本地运行摘要和状态。
- /resume <runId>：从检查点恢复一个非终态运行。
- /exit 或 /quit：退出。

## Workflow 状态与恢复语义

Run 状态为 CREATED → RUNNING → SUCCEEDED/FAILED/CANCELLED。每次状态变化附时间和失败类别；模型请求和工具调用各自有稳定 stepId、状态、起止时间和耗时。runId 使用 UUID。

RunStore 是存储边界，当前提供：

- InMemoryRunStore：Fake Store，用于无文件副作用的单元测试。
- JsonFileRunStore：本地持久化实现，每个 Run 一个 JSON 文件，写入使用临时文件和原子替换，并尽量设置 owner-only 文件/目录权限。

工具调用检查点分为：

1. 模型响应和全部工具请求先以 PENDING 保存。
2. 执行某个工具前，将其改为 IN_PROGRESS 并再次保存。
3. 执行完成后保存结果消息和 SUCCEEDED / FAILED / INDETERMINATE 步骤状态。

恢复时只执行 PENDING 工具。发现旧进程留下的 IN_PROGRESS 工具步骤时，系统将其视为结果未知，记录 INDETERMINATE，把“未自动重放”的错误结果交回模型，绝不默默重放。工具收到中断不等于外部副作用已经撤销；没有工具端幂等键或事务协议时，本项目不保证 exactly-once。模型请求没有工具副作用，若进程在请求中退出，恢复可能重发该请求并产生重复模型费用。

检查点需要保存消息历史和待执行工具请求，所以本地 JSON 可能包含用户输入、模型文本、工具参数和工具结果。运行步骤/日志不会记录完整工具参数或原始异常内容；API Key 不属于 RunRecord。请勿将密钥或不应持久化的敏感内容放入任务输入、工具参数或工具结果，并保护 .agent-runs/ 目录。

## 预算、重试与错误

默认 Runtime 配置：

- 最多 10 次模型请求，重试也计入总数。
- 最多 20 次工具调用。
- 最多 2 次模型重试。
- 单个任务总 Deadline 为 5 分钟。
- 单个模型请求超时由 DeepSeek 客户端设置为 60 秒。
- 单次工具等待最多 10 秒，并且不会超过任务剩余时间。

DeepSeek SDK 的内部重试已关闭，由 Runtime 统一计数。限流、服务端和网络/超时错误可在剩余模型预算和 Deadline 内重试；认证、请求格式等终止错误不重试。运行记录只保留失败类别，不持久化供应商异常正文。

工具超时会取消等待并中断执行线程，但底层工具可能忽略中断或已经完成副作用，因此步骤是 INDETERMINATE。系统不会把这种状态当成可安全重试的失败。工具执行异常也会以脱敏类别和通用错误反馈给模型。

## 可观测记录

RunRecord 和步骤记录至少包含：

- runId、stepId、状态变化与时间；
- 模型请求数、工具调用数、失败类别；
- 每个模型/工具步骤的耗时；
- 总运行时长、是否恢复；
- Token 用量：模型响应能提供时记录 prompt/completion 数；任一请求用量缺失或请求失败时，全任务 Token 总量显示为未知，不进行估算。

CLI /runs 展示简要汇总。日志只输出 ID、工具名、状态、耗时、失败类别和用量是否可知；不输出 API Key、消息正文、工具参数或原始异常文本。本项目不接入外部监控平台。

## 工具与分层

- ModelClient：模型厂商适配边界；DeepSeek 实现负责协议映射、超时和模型错误分类。
- AgentRuntime：手写模型/工具循环、Run 状态机、Checkpoint、预算、恢复和日志。
- RunStore：检查点存储抽象；内存和本地 JSON 两种实现。
- AgentTool / ToolRegistry / ToolExecutor：工具契约、注册、参数子集校验、执行和异常分类。
- 演示工具：本地四则运算、Java 时区时间、固定模拟天气。

工具选择仍由模型完成，不通过 Java 关键词分流。工具 Schema 校验覆盖本演示需要的 object、必填字段、基础类型、数值范围和 enum，不是完整 JSON Schema 实现。

## 固定回归评测

运行全部单元测试：

    mvn test

只运行 Workflow 评测及持久化恢复样例：

    mvn -Dtest=AgentWorkflowEvaluationTest,FileCheckpointRecoveryTest,JsonFileRunStoreTest test

样例使用 Fake ModelClient、Fake/In-memory Store 和确定性工具，不读取真实 API Key，不访问真实模型。覆盖普通回答、模型可重试错误、工具调用预算、工具执行失败、任务 Deadline、取消、工具超时未知状态、PENDING 工具恢复、IN_PROGRESS 工具不重放，以及 JSON 文件存取和重启后恢复。断言检查最终状态/文本、工具类别和次数、步骤耗时/用量字段、是否恢复及失败类别。

这些固定样例验证的是已知控制流程和边界行为；它们不能证明真实模型会正确选择工具、工具结果适合业务，也不能代表 Agent 整体回答质量、真实服务可用性或生产安全性。
