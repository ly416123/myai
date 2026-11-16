# 阶段交付与验收约束

## 项目定位与架构原则

- MyAi 是面向企业应用演进的 Agent Runtime 项目，不是学习项目。设计和实现应按企业系统要求审视可靠性、持久化一致性、并发与多实例、恢复、权限与敏感数据、可观测性、测试、部署升级和运维。
- Docker、数据库、消息队列和远程主机资源均可使用。按业务需求、容量和运维代价选型，不因早期阶段的范围限制而视为长期禁用，也不为了技术堆叠而无条件引入。
- 需要在多个可行实现或架构方案间决策时，结合本项目目标、已确认范围、实际资源和运维成本，直接采用最推荐方案继续推进，不为常规技术选择暂停等待用户答复。将决定、主要依据、适用范围和重要限制写入本文件或对应架构文档。涉及超出现有授权范围的外部写入、不可逆破坏或扩大资源影响范围时，仍须遵守既有授权和资源边界。
- HTTP API 与生产身份认证/授权暂不交付。应用提交边界使用 `CallerContext` 与 `RunSubmissionPolicy` 预留调用方扩展点；当前 CLI 只传递 `local-cli` 标识，生产策略显式为无认证策略，Fake 测试覆盖上下文传递和拒绝时不落库。该标识未认证、不持久化、不写日志，不代表已实现身份验证、租户隔离或资源级授权；后续只有在真实接入方式确定后再接入可信身份提供方和相应策略。
- 已完成阶段的限制只描述该阶段实际交付，不构成后续架构的永久约束。尤其当前本地 JSON RunStore 和单进程调度不是企业生产部署的最终方案。
- [阶段 1–3 企业级架构升级方案](docs/architecture/enterprise-migration-roadmap.md)是当前企业化改造基线。后续实现要先更新方案中的事实/决策状态，再按优先级落地；文档计划、代码现状和已验收能力必须明确区分。
- `ly@192.168.122.55` 后续称为 L5 环境，Kubernetes 配置位于 `~/.kube/l5-k3s.yaml`（context `l5`）。该环境为单节点 K3s v1.36.4+k3s1，4 CPU、约 7.15 GiB 内存；2026-11-16 通过 kubeconfig 复核节点 Ready。该日较早时约 80.7 GiB 根文件系统可用；实际容量需每次核验。MyAI namespace 运行 Worker 2 副本、模型出站代理 2 副本，以及 PostgreSQL、OTel Collector、Prometheus、Tempo、Grafana 各 1 副本。其他业务 Deployment/StatefulSet 保持 0 副本，既有 CronJob 保持 suspended；系统组件与 MyAI 工作负载运行。其他命名空间的配置、PVC、已完成/失败 Pod 不属于 MyAI，不要删除或启动；部署、扩容或变更前需重新核验集群和资源余量。
- 后续迭代按本机、GitHub Actions 和 L5 的实际资源分工，不把重任务默认堆到同一侧。开始构建、集成测试或部署前重新检查宿主机可用磁盘、Colima profile 的 CPU/内存/Docker 数据盘、L5 节点压力/实际使用/requests 与 limits、PVC 和其他工作负载；不要依据旧容量快照。
- **较早历史快照（2026-11-16，已被当日后续核验取代）：** 本机当时仅约 3 GiB 可用空间，Colima Docker 数据卷接近满载；不得再将该快照用作当前容量结论。
- **当前资源快照（2026-11-16 后续复核）：** 本机 Apple Silicon 为 10 逻辑 CPU、16 GiB 内存，macOS Data 卷约 108 GiB 可用。当前 `colima list` 显示 `default` profile 正在运行（2 CPU/4 GiB）；Colima 内 `/var/lib/docker` 约 99 GiB，其中约 93 GiB 可用。最近一次全量 Testcontainers 验证后，`docker system df` 显示 4 个镜像（合计约 1.407 GB）、无容器/volume/build cache。完整本地 Maven `clean verify` 已实际通过（180 项测试，0 失败/错误/跳过）；PostgreSQL 与 OTel Collector Testcontainers 集成测试均实际执行。首次下载 OTel Collector 的 104 MB 镜像层曾耗时约 7 分钟；本轮首次拉取 Ryuk 约 55 秒，全量验证约 1 分 13 秒。后续以每次操作前实测为准；不要运行全局 `docker system prune`，不要清理来源不明的镜像、volume 或 build cache。
- **2026-11-16 现场复核：** L5 实际使用约 12% CPU（480m）、45% 内存（3353Mi），可用内存约 4.8GiB，根文件系统约 81 GiB 可用；MemoryPressure/DiskPressure/PIDPressure 均为 False。requests 为 33% CPU/38% 内存，limits 合计 150% CPU/91% 内存。MyAI 9 个 Pod 均 Ready。PostgreSQL、Prometheus、Tempo、Grafana 与 Collector 前序容器日志显示它们在 09:36:20（Asia/Shanghai）收到正常终止信号；Worker 有 `Error/143`、代理有 `Unknown/255` 终止记录。L5 主机报告启动时间为 09:36:45，宿主与 Kubernetes Node 的 Boot ID 一致；没有观察到 OOM 或节点压力证据。这些证据与主机重启期间的集群级停止相符，但未确认重启发起方。Prometheus 规则 10 条均健康、2 个 target 均 UP、无活动告警，Tempo `/ready` 正常，Grafana 数据库状态为 `ok`。非 MyAI 业务 Deployment/StatefulSet 维持 0 副本，既有 CronJob 维持 suspended；不要改动其他命名空间资源。
- **资源放置（平衡方案）：** 本机承担日常开发、完整 Maven `clean verify`（包括 Testcontainers）及开发期间的镜像构建/调试。GitHub Actions 是 PR/main 全量验证、主分支镜像构建与 GHCR 发布的最终验收依据；本地结果用于快速反馈，不替代 CI。L5 只承担已部署系统的有界 smoke/integration、数据库/Trace/Worker 检查和恢复流程验证；不在 L5 做并行压测、常驻构建或无上限测试。每次重任务前重查宿主和 Colima 磁盘/内存；如果容量或内存不足则转交 GitHub Actions，不通过清理共享 Docker 数据来腾空间。L5 运行额外 Job 前核验当前节点、PVC、limits 和其他工作负载，一次只执行一个有明确资源上限的场景。
- 每阶段总结应把“该阶段明确范围内的验收结果”和“面向企业目标仍未解决的工程缺口”分开陈述，不把阶段通过等同于生产就绪。

## 阶段完成后的总结文档

每个开发阶段完成后，必须先进行验收。只有验收通过，才将该阶段标记为完成，并在项目的 `docs/stages/` 目录新增阶段总结文档，文件名使用 `phase-NN-summary.html`，例如 `phase-02-summary.html`。

阶段总结必须记录可核对的项目事实，至少包括：

1. 阶段目标和验收结论。
2. 上一阶段遗留的问题，以及它们对本阶段目标的影响。
3. 本阶段解决的问题及对应实现方式。
4. 关键技术选型和设计决策、考虑过的主要替代方案，以及选择当前方案的原因。
5. 实际效果，包括可观察到的功能变化、运行行为、指标或其他结果；没有测量的数据不得臆测。
6. 验收方式、实际执行的命令、测试数量和结果；没有运行的验证不得写成已通过。
7. 尚存限制、风险和建议的下一阶段方向。
8. 该阶段完成时的项目整体架构图，必须展示分层体系、主要组件职责和关键依赖/数据流。架构图应反映该阶段实际交付的形态，而不是只画目标架构或当前最新架构；阶段总结必须说明它对应的时间点与范围。

架构图可以直接嵌入 HTML，也可以拆成多张图或独立文件；若拆分，必须从对应阶段总结提供清楚的标题、用途说明和可点击链接。复杂阶段可以用多张图分别展示逻辑分层、运行部署和关键流程，避免把不同视角挤进一张难以阅读的图。

文档使用中文 HTML，采用语义化结构并保证本地浏览器可直接打开。更新 README 中的阶段文档索引，便于查找。

若验收未通过，不得将阶段总结写成“阶段完成”或“验收通过”；应记录失败项、证据、修复计划，并在修复后重新验收。阶段文档应在验收结论形成后生成，避免把计划写成实际结果。
