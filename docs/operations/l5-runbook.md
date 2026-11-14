# MyAI L5 运维手册

本文适用于当前 L5 集成环境（K3s context `l5`、namespace `myai`）。命令以只读检查为主；回滚、重启和恢复操作会改变运行状态，执行前先确认影响范围。

## 当前运行边界

最近核对时间：2026-11-14。

- 单节点 K3s：节点故障会同时影响 Worker、PostgreSQL 和监控；当前不具备节点级高可用或异地恢复能力。
- Worker 2 副本，当前镜像为 `docker.io/library/myai-agent:l5-20261114-runtime-current`（基于完整 Dockerfile 镜像添加当前应用 JAR 层；manifest `sha256:0ce43b84944beac5d2895e1f7760d895680e151ed4cebb489ae64ce64edd7378`、imageID `sha256:2a96238b72f79da559e8770815a70f5ac95eec8ea500bcc81c6bfda8e141eaa3`；2/2 Ready、0 次重启；Pod 内 JAR SHA-256 与当前本地构建产物一致）；模型出站经 Squid 代理，只允许 `api.deepseek.com:443`。PostgreSQL、Collector、Prometheus、Tempo、Grafana 各 1 副本。
- 2026-11-14 发现先前源码中的 PostgreSQL、OTel Collector、Prometheus、Tempo 和 Grafana digest 只是与 L5 containerd 本地引用一致，并非公开 Registry 可解析的 manifest。当前源码固定 Registry 的 `linux/amd64` 平台 manifest digest，并由 `bash ops/tests/image-digests-test.sh` 校验。L5 节点无法直接访问 Docker Hub；镜像经 OCI 归档导入并核对后，Grafana、Tempo、PostgreSQL 已按服务滚动，Collector 与 Prometheus 原有 imageID 已匹配。应用清单时必须使用 `deploy/overlays/l5`，通用 `deploy/k8s` 含有占位 Worker/代理镜像。PostgreSQL 仍为 16.15，数据 PVC 未变；Prometheus v3.16.0 按项目决定维持不变。
- Worker 通过 OTel Collector 将 Trace 写入 Tempo，Grafana 提供内部查询入口。2026-11-14 验收时 Tempo/Grafana 均 1/1 Ready；合成 Trace 从 Collector OTLP HTTP 接收（200）到 Tempo Trace ID 查询（200），Grafana Tempo 数据源健康检查为 `OK`。同日受控故障演练中，Tempo 暂停期间 Collector 接受一条合成 Trace；Collector 随后在 Tempo 仍暂停时重启，Tempo 恢复后 Trace ID 查询返回 1 个 span。此结果只证明该次进程重启和短暂后端不可用场景下的队列恢复，不证明节点/磁盘故障恢复或零丢失。Trace resource 标记 `deployment.environment.name=l5`；Prometheus 对 Collector 和 Tempo 的活动抓取目标带 `environment=l5`。Tempo 使用单副本、10 GiB `local-path` PVC、保留 7 天，并显式设置 `query_frontend.search.max_result_limit=262144`；2026-11-14 已验证运行时配置包含该限制且 `/ready` 返回 200。Grafana 使用临时本地数据目录，仪表盘/用户状态在 Pod 重建后不保证保留。Collector 文件发送队列为 1 GiB，本地持久化，单批发送重试窗口最长 1 小时。首次部署期间超过原 5 分钟重试窗口的少量 Trace 已被 Collector 丢弃，无法恢复。PVC 不能承受 L5 单节点/磁盘损坏；没有备份或异地恢复。Grafana/Tempo 仅为 ClusterIP，不对集群外开放；Grafana 匿名访问关闭。
- PostgreSQL 16 使用 5 GiB `local-path` PVC。当前没有数据库备份与恢复流程；卷或节点损坏可能导致 Run 与检查点丢失。CA 签名私钥托管尚未建设。
- Prometheus v3.16.0 使用 7 天本地时序保留；版本按项目决策维持不变。
- 中间件镜像 CVE 按项目约束不作为当前功能验收项；不安排因此产生的版本升级或自维护派生镜像。PostgreSQL 16.15、Prometheus v3.16.0、Tempo 2.10.10、Grafana 12.4.15 按当前部署继续运行；既有 Trivy 扫描数据仅作历史信息记录，不代表漏洞已修复或安全认证。
- Run 自动清理保持关闭（`AGENT_RUN_RETENTION_PERIOD=PT0S`）。不要手工删除 Run、消息、步骤或租约记录。

Grafana ClusterIP 内部提供 MyAI Runtime Overview 仪表盘（uid `myai-runtime-overview`），显示可恢复 Run 队列、最老等待、过期租约、近 5 分钟失败 Run、数据库连接池使用率、Collector 和 Tempo 可用性（共 7 个面板）。只读查看方式：

```bash
kubectl --kubeconfig ~/.kube/l5-k3s.yaml --context l5 -n myai port-forward svc/myai-grafana 3000:3000
```

浏览器打开 `http://localhost:3000/d/myai-runtime-overview/myai-runtime-overview`，使用 Grafana 管理员 Secret 中的账户登录。仪表盘由 Git 管理的 Grafana provisioning 文件加载；不要在 UI 中编辑以免产生易失配置。该仪表盘和 Prometheus 数据只用于 L5 集成观察，未定义为业务 SLO，也没有告警通知接收端。
- L5 Prometheus 保留告警规则和本地状态，但没有 Alertmanager 或通知接收端。告警阈值是运维默认值，不是已批准的业务 SLO。

## 1. 建立只读检查上下文

```bash
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/l5-k3s.yaml}"
CTX=l5
NS=myai

kubectl --context "$CTX" get nodes -o wide
kubectl --context "$CTX" -n "$NS" get deploy,statefulset,cronjob,pvc -o wide
kubectl --context "$CTX" -n "$NS" get pods -o wide
kubectl --context "$CTX" -n "$NS" get events --sort-by=.lastTimestamp
```

检查单个 Pod 时使用 `kubectl describe pod <pod>`；查看 Worker 最近日志：

```bash
kubectl --context "$CTX" -n "$NS" logs deploy/myai-agent-worker --all-pods=true --prefix --since=15m
```

日志设计为不输出消息正文、API Key、工具参数和原始异常文本。仍应按内部运行数据处理日志，不要把完整日志贴到公开工单或聊天中。不要执行 `kubectl get secret -o yaml`、`env` 或 shell 跟踪来排查密钥。

## 2. 检查 Run 状态

数据库只读汇总不读取用户输入、模型消息或工具参数：

```bash
kubectl --context "$CTX" -n "$NS" exec myai-postgres-0 -- \
  psql -U myai -d myai -c \
  "SELECT status, count(*) FROM agent_run GROUP BY status ORDER BY status;"
```

对具体 Run，可在受控 CLI 中使用 `/runs` 查看最近运行摘要，使用 `/tool-audit <runId>` 检查工具状态变化。审计记录不含参数、结果正文或异常原文。不要直接修改 `agent_run`、`run_step` 或 `tool_execution_audit` 表来“解卡”。

`RUNNING` 租约过期后，Worker 会按 fencing 规则恢复检查点。模型请求中断后可能重新调用模型并产生重复费用；`IN_PROGRESS` 工具调用恢复为结果未知（`INDETERMINATE`），Runtime 不会自动重放它。当前演示工具没有外部副作用；若未来接入外部副作用工具，先检查目标系统，再按该工具的幂等/补偿流程处置。不得把取消 Pod 或线程视为撤销外部操作。

## 3. 检查监控和告警状态

Prometheus 内置控制台可查看队列、租约、近期失败和连接池指标：

```bash
kubectl --context "$CTX" -n "$NS" port-forward svc/myai-prometheus 9090:9090
```

在本机浏览器打开 `http://localhost:9090/consoles/agent-overview.html`。终止端口转发时按 `Ctrl+C`。

查看规则是否健康，以及当前是否存在 Pending/Firing 告警：

```bash
PROM_POD="$(kubectl --context "$CTX" -n "$NS" get pod \
  -l app.kubernetes.io/name=myai-prometheus \
  -o jsonpath='{.items[0].metadata.name}')"
kubectl --context "$CTX" -n "$NS" exec "$PROM_POD" -- \
  wget -qO- http://localhost:9090/api/v1/rules
kubectl --context "$CTX" -n "$NS" exec "$PROM_POD" -- \
  wget -qO- http://localhost:9090/api/v1/alerts
```

当前 10 条规则覆盖 Collector/Tempo 抓取目标不可用、队列等待超过 300 秒并持续 5 分钟、过期租约持续 2 分钟、5 分钟窗口内有失败 Run 且持续 1 分钟、数据库连接池使用率超过 90% 并持续 5 分钟、Worker JVM Heap 使用量占上限汇总超过 85% 并持续 5 分钟、Worker 轮询失败计数在 5 分钟内增加并持续 1 分钟，以及 Worker 队列深度指标持续缺失 2 分钟、Worker 成功轮询心跳副本数低于期望副本数并持续 2 分钟。Heap 告警按 `service_instance_id` 区分 Worker；它不等同于容器 RSS 或 cgroup 内存，也不覆盖 JVM native 内存。Worker 轮询失败只记录无标签计数，不记录异常正文；队列深度指标缺失告警可发现整个 Worker 指标链路中断；副本数告警通过 OTLP `service.instance.id` 生成 `service_instance_id` 标签区分 Pod。Collector 对指标序列保留 5 分钟，副本退出后该告警通常约 7 分钟后触发；这不是即时探活。Tempo target down 告警在 `up{job="myai-tempo"} == 0` 持续 2 分钟后触发。规则细节以 [告警配置](../../deploy/k8s/base/agent-alerts.yaml) 为准；Kustomize 从该文件生成 Prometheus ConfigMap。告警只保存在 Prometheus；没有外部通知路径。

## 4. 常见故障处理

### Worker 不 Ready 或队列持续等待

1. 检查 Worker Pod 的 Ready 状态、事件和最近 15 分钟日志。
2. 检查 PostgreSQL Pod Ready、PVC Bound，以及 Collector/代理是否可用。
3. 若数据库轮询失败，Worker readiness 会失败，但轮询线程仍运行时 liveness 不会因数据库短暂故障而触发重启。先恢复数据库连通性，不要反复重启 Worker。
4. 若应用进程崩溃，先确认数据库状态与活动 Run，再检查当前镜像和 Deployment 历史。需要回退 Worker 时按下方“Worker 版本回滚”操作。

### Run 租约过期或 Worker 被意外终止

1. 检查数据库 Pod 与 Worker 副本是否 Ready。
2. 等待租约到期后由另一 Worker 恢复；通过 `/runs` 查看 Run 是否恢复以及最终状态。
3. 若包含工具步骤，使用 `/tool-audit <runId>` 查看 `INDETERMINATE` 状态。确认操作是否已在目标系统发生前，不要手动恢复/重放该工具步骤。
4. 不要直接清除租约、递增 fencing token 或把 Run 状态改回 `CREATED`。

### Provider 调用失败

1. 检查 Worker 与 `myai-model-egress-proxy` 是否 Ready，并查看这两个 Deployment 的最近日志。
2. L5 egress 只允许经代理访问精确域名 `api.deepseek.com:443`；其他域名被代理拒绝是预期策略，不要临时开放公网或绕过代理。
3. 检查 Provider Secret 是否存在及以只读文件挂载；不要输出 Secret 内容或将 Key 放入命令行参数。
4. 401 通常表示 Provider 未接受凭据；代理拒绝请求时会返回 403。确认配置后再提交新的 Run，不要对原 Run 做数据库级重放。

### PostgreSQL 不可用

1. 检查 `myai-postgres-0` 事件、日志、PVC 状态及节点磁盘容量。
2. 暂停新任务提交；不要删除 PVC、强删数据库 Pod 或手工修改 Run 表。
3. 数据库恢复后确认 PostgreSQL Ready、Flyway/Worker 连接恢复，并观察租约接管。短暂数据库中断演练已有成功证据，但这不覆盖磁盘损坏或节点丢失。
4. 如果怀疑数据损坏，先保留现场并停止进一步写入。当前没有受支持的 MyAI 数据库备份/恢复流程；节点或存储故障造成的数据丢失没有应用级恢复路径。

### Collector 或 Prometheus 不可用

1. 检查对应 Pod、事件、日志与 Prometheus Targets API；Prometheus 是单副本，升级/重启期间本地查询可能短暂不可用。
2. 规则健康与告警状态可通过上节 Prometheus API 只读查看。当前没有通知接收端，Pending/Firing 状态需要人工查看。
3. 只读检查 Collector、Tempo 与 Grafana 状态；只有后两者 Ready 后再查询 Trace：

   ```bash
   kubectl --context "$CTX" -n "$NS" get deploy myai-otel-collector myai-tempo myai-grafana
   kubectl --context "$CTX" -n "$NS" get pvc myai-tempo-data myai-otel-collector-queue
   ```

   后端 Ready 后，本地转发 Grafana：

   ```bash
   kubectl --context "$CTX" -n "$NS" port-forward svc/myai-grafana 3000:3000
   ```

   打开 `http://localhost:3000`，用户名为 `admin`。管理员密码保存在集群 Secret `myai-grafana-admin` 中，可在受控终端读取：

   ```bash
   kubectl --context "$CTX" -n "$NS" get secret myai-grafana-admin \
     -o jsonpath='{.data.admin-password}' | base64 --decode
   printf '\n'
   ```

   不要将密码写入仓库、命令历史或聊天。Grafana Explore 中选择 `MyAI Tempo` 数据源查询 Trace。若镜像仍无法拉取，先检查节点到镜像仓库的访问与容器运行时 registry 配置；在 Tempo Ready 前 Trace 只在 Collector 队列中等待，不要据此重放 Run。
4. 监控故障不应通过修改 Run 状态、租约或工具审计记录来处理。

## 5. Worker 版本回滚

回滚只针对 Worker Deployment，不会回退 Flyway/PostgreSQL schema。已验证流程是：Worker Deployment 从代理出站版本回滚到前一个 Worker 镜像后恢复；再应用 L5 overlay 可回到仓库声明的当前版本。之前的回滚演练没有执行数据库 schema 回滚。

先查看历史并确认目标修订对应的镜像：

```bash
kubectl --context "$CTX" -n "$NS" rollout history deploy/myai-agent-worker
kubectl --context "$CTX" -n "$NS" get rs \
  -l app.kubernetes.io/component=worker \
  -o jsonpath='{range .items[*]}{.metadata.annotations.deployment\.kubernetes\.io/revision}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'
kubectl --context "$CTX" -n "$NS" get deploy myai-agent-worker \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

经变更负责人确认后，将 `REVISION` 替换为已核验的历史修订号：

```bash
kubectl --context "$CTX" -n "$NS" rollout undo deploy/myai-agent-worker --to-revision=REVISION
kubectl --context "$CTX" -n "$NS" rollout status deploy/myai-agent-worker --timeout=180s
```

如需恢复仓库当前声明版本：

```bash
kubectl --kubeconfig "$KUBECONFIG" --context "$CTX" apply -k deploy/overlays/l5
kubectl --context "$CTX" -n "$NS" rollout status deploy/myai-agent-worker --timeout=180s
kubectl --context "$CTX" -n "$NS" get deploy myai-agent-worker -o wide
```

确认 Worker 2/2 Ready、镜像与 overlay 一致，PostgreSQL/Collector/Prometheus/代理均 Ready，再允许提交新 Run。回滚应用版本不会撤销数据库迁移；若旧镜像不兼容当前 schema，必须停止回滚并评估向前修复。当前没有数据库备份/恢复能力，不能把数据库恢复当作回滚步骤。

## 6. 验收与限制

- Prometheus 规则自动回归：`bash ops/tests/prometheus-alerts-test.sh`。
- Kubernetes 清单本地渲染：`kubectl kustomize deploy/overlays/l5`。
- Maven 和 PostgreSQL 集成测试（Colima 示例）：

  ```bash
  export DOCKER_HOST="${DOCKER_HOST:-unix://${HOME}/.colima/default/docker.sock}"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  mvn --batch-mode --no-transfer-progress clean verify
  ```

  保持 Testcontainers Ryuk 清理容器启用；socket override 让 Ryuk 在 Colima Linux VM 内挂载正确的 Docker socket。构建后检查 Surefire 汇总的 skipped 数，避免 Docker 探测问题让集成测试静默跳过。
- Tempo Trace 保留期限当前为 7 天，使用同节点本地 PVC；Collector 磁盘队列及 Tempo 查询链路已完成一次合成数据验收。数据库与 Trace 均无备份能力；节点或磁盘故障可造成不可恢复的数据丢失。RPO/RTO、生产 SLO、数据分类/保留期限仍需业务确认。
- 当前不提供 PostgreSQL 备份/恢复能力。HTTP API/生产认证授权、告警通知、CA 签名密钥托管/轮换均按当前决策不在本轮实施范围。
