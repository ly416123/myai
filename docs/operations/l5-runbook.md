# MyAI L5 运维手册

本文适用于当前 L5 集成环境（K3s context `l5`、namespace `myai`）。命令以只读检查为主；回滚、重启和恢复操作会改变运行状态，执行前先确认影响范围。

## 当前运行边界

最近核对时间：2026-11-13。

- 单节点 K3s：节点故障会同时影响 Worker、PostgreSQL 和监控；当前不具备节点级高可用或异地恢复能力。此前生成的归档 PVC 保留但不属于当前运行保障。
- Worker 2 副本；模型出站经 Squid 代理，只允许 `api.deepseek.com:443`。PostgreSQL、Collector、Prometheus 各 1 副本。
- PostgreSQL 16 使用 5 GiB `local-path` PVC。按项目决策，备份/恢复脚本、定时 CronJob 和归档监控已移除；此前归档 PVC 与数据保留但不挂载、不再更新或清理。当前数据库没有应用级备份与恢复保障。CA 签名私钥托管尚未建设。
- Prometheus v3.16.0 使用 7 天本地时序保留；版本按项目决策维持不变，已知 HIGH CVE-2026-78661 风险见升级路线图。
- Run 自动清理保持关闭（`AGENT_RUN_RETENTION_PERIOD=PT0S`）。不要手工删除 Run、消息、步骤或租约记录。
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

当前 5 条规则覆盖 Collector 抓取目标不可用、队列等待超过 300 秒并持续 5 分钟、过期租约持续 2 分钟、5 分钟窗口内有失败 Run 且持续 1 分钟，以及数据库连接池使用率超过 90% 并持续 5 分钟。规则细节以 [告警配置](../../deploy/k8s/base/agent-alerts.yaml) 为准；Kustomize 从该文件生成 Prometheus ConfigMap。告警只保存在 Prometheus；没有外部通知路径。

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
4. 如果怀疑数据损坏，先保留现场并停止进一步写入。当前没有受支持的 MyAI 数据库备份/恢复流程；此前归档 PVC 虽保留但未经当前恢复流程验证，不应当作可恢复承诺。

### Collector 或 Prometheus 不可用

1. 检查对应 Pod、事件、日志与 Prometheus Targets API；Prometheus 是单副本，升级/重启期间本地查询可能短暂不可用。
2. 规则健康与告警状态可通过上节 Prometheus API 只读查看。当前没有通知接收端，Pending/Firing 状态需要人工查看。
3. 监控故障不应通过修改 Run 状态、租约或工具审计记录来处理。

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

确认 Worker 2/2 Ready、镜像与 overlay 一致，PostgreSQL/Collector/Prometheus/代理均 Ready，再允许提交新 Run。回滚应用版本不会撤销数据库迁移；若旧镜像不兼容当前 schema，必须停止回滚并走数据库恢复/向前修复评估。

## 6. 验收与限制

- Prometheus 规则自动回归：`bash ops/tests/prometheus-alerts-test.sh`。
- Kubernetes 清单本地渲染：`kubectl kustomize deploy/overlays/l5`。
- Maven 和 PostgreSQL 集成测试（Colima 示例）：

  ```bash
  export DOCKER_HOST="${DOCKER_HOST:-unix://${HOME}/.colima/default/docker.sock}"
  export TESTCONTAINERS_RYUK_DISABLED=true
  mvn --batch-mode --no-transfer-progress clean verify
  ```
- 当前 L5 单节点和同节点 PVC 不提供节点故障恢复目标；RPO/RTO、生产 SLO、数据分类/保留期限仍需业务确认。
- 当前不提供 PostgreSQL 备份/恢复能力；原归档 PVC 保留但不纳入清单、不挂载且未经恢复演练。HTTP API/生产认证授权、告警通知、CA 签名密钥托管/轮换均按当前决策不在本轮实施范围。
