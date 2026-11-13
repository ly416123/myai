package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerDeploymentContractTest {
    @Test
    void dockerBaseImagesAreDigestPinnedAndMonitoredForUpdates() throws Exception {
        String runtimeDockerfile = Files.readString(Path.of("Dockerfile"));
        String proxyDockerfile = Files.readString(Path.of("ops/egress-proxy.Dockerfile"));
        String dependabot = Files.readString(Path.of(".github/dependabot.yml"));

        assertTrue(runtimeDockerfile.lines().anyMatch(line ->
                line.matches("FROM eclipse-temurin:21-jre@sha256:[a-f0-9]{64}")));
        assertTrue(proxyDockerfile.lines().anyMatch(line ->
                line.matches("FROM ubuntu:26\\.04@sha256:[a-f0-9]{64}")));
        assertTrue(dependabot.matches("(?s).*package-ecosystem: docker\\s+directory: /\\s+schedule:\\s+interval: weekly.*"));
        assertTrue(dependabot.matches("(?s).*package-ecosystem: docker\\s+directory: /ops\\s+schedule:\\s+interval: weekly.*"));
    }

    @Test
    void imageBuildCannotCopyLocalCredentialsAndRunsAsNonRoot() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String ignore = Files.readString(Path.of(".dockerignore"));
        String gitignore = Files.readString(Path.of(".gitignore"));

        assertTrue(dockerfile.contains("USER 10001:10001"));
        assertTrue(dockerfile.contains("COPY target/minimal-agent-runtime-1.0-SNAPSHOT.jar /app/app.jar"));
        assertTrue(dockerfile.contains("COPY eval/scenarios-v1.json /app/eval/scenarios-v1.json"));
        assertTrue(dockerfile.contains("COPY eval/scenarios-v2.json /app/eval/scenarios-v2.json"));
        assertFalse(dockerfile.contains("COPY . ."));
        assertTrue(ignore.contains("deepseek.key"));
        assertTrue(ignore.contains(".env"));
        assertFalse(ignore.contains("backup"));
        assertFalse(ignore.contains("*.dump.age"));
        assertFalse(gitignore.contains("backup"));
        assertFalse(gitignore.contains("*.dump.age"));
    }

    @Test
    void jobUsesSecretsAndRestrictedContainerSecurity() throws Exception {
        String manifest = Files.readString(Path.of("deploy/k8s/base/agent-task-job.yaml"));

        assertTrue(manifest.contains("kind: Job"));
        assertTrue(manifest.contains("name: myai-agent-task"));
        assertTrue(manifest.contains("secretName: myai-provider"));
        assertTrue(manifest.contains("name: myai-database"));
        assertTrue(manifest.contains("runAsNonRoot: true"));
        assertTrue(manifest.contains("automountServiceAccountToken: false"));
        assertTrue(manifest.contains("name: myai-registry"));
        assertTrue(manifest.contains("readOnlyRootFilesystem: true"));
        String overlay = Files.readString(Path.of("deploy/overlays/l5/kustomization.yaml"));
        assertTrue(overlay.contains("count: 2"));
        assertTrue(overlay.contains("newTag: l5-20261113"));
        assertFalse(overlay.contains("myai-postgres-backup"));
        assertFalse(Files.exists(Path.of("deploy/k8s/base/postgres-backup-cronjob.yaml")));
        assertTrue(manifest.contains("backoffLimit: 0"));
        assertFalse(manifest.contains("kind: Deployment"));
        assertProviderCredentialIsMountedAsReadOnlyFile(manifest);
    }

    @Test
    void workerDeploymentUsesPostgresCoordinationAndBoundedReplicaResources() throws Exception {
        String manifest = Files.readString(Path.of("deploy/k8s/base/agent-worker-deployment.yaml"));

        assertTrue(manifest.contains("kind: Deployment"));
        assertTrue(manifest.contains("replicas: 2"));
        assertTrue(manifest.contains("value: worker"));
        assertTrue(manifest.contains("AGENT_WORKER_CONCURRENCY"));
        assertTrue(manifest.contains("name: myai-runtime"));
        assertTrue(manifest.contains("secretName: myai-provider"));
        assertTrue(manifest.contains("name: myai-database"));
        assertTrue(manifest.contains("runAsNonRoot: true"));
        assertTrue(manifest.contains("automountServiceAccountToken: false"));
        assertTrue(manifest.contains("name: myai-registry"));
        assertTrue(manifest.contains("readOnlyRootFilesystem: true"));
        assertTrue(manifest.contains("readinessProbe:"));
        assertTrue(manifest.contains("/tmp/myai-worker-health"));
        assertTrue(manifest.contains("startupProbe:"));
        assertTrue(manifest.contains("livenessProbe:"));
        assertTrue(manifest.contains("mountPath: /var/run/secrets/database-ca"));
        String runtime = Files.readString(Path.of("deploy/k8s/base/l5-postgres.yaml"));
        assertTrue(runtime.contains("sslmode=verify-full"));
        assertTrue(manifest.contains("/tmp/myai-worker-live"));
        assertFalse(manifest.contains("httpGet:"));
        assertProviderCredentialIsMountedAsReadOnlyFile(manifest);
    }

    @Test
    void prometheusAlertTemplateHasBoundedWorkerAndDatabaseSignals() throws Exception {
        String rules = Files.readString(Path.of("deploy/k8s/base/agent-alerts.yaml"));

        assertTrue(rules.contains("MyAiCollectorTargetDown"));
        assertTrue(rules.contains("AgentWorkerQueueStalled"));
        assertTrue(rules.contains("myai_agent_worker_queue_oldest_seconds"));
        assertTrue(rules.contains("AgentExpiredRunLeases"));
        assertTrue(rules.contains("myai_agent_worker_lease_expired_leases"));
        assertTrue(rules.contains("AgentRecentRunFailures"));
        assertTrue(rules.contains("myai_agent_worker_run_failed_recent_runs"));
        assertTrue(rules.contains("myai_hikaricp_connections_active"));
        assertTrue(rules.contains("myai_agent_worker_queue_oldest_seconds"));
        assertTrue(rules.contains("myai_agent_worker_lease_expired_leases"));
        assertTrue(rules.contains("myai_agent_worker_run_failed_recent_runs"));
        assertTrue(rules.contains("myai_hikaricp_connections_active"));
        assertTrue(Files.readString(Path.of("src/test/resources/otel/collector-config.yaml"))
                .contains("namespace: myai"));
        assertFalse(rules.contains("run_id"));
    }

    @Test
    void backupJobsAndTheirStatusExporterAreAbsentFromDeployment() throws Exception {
        String foundation = Files.readString(Path.of("deploy/k8s/base/l5-foundation.yaml"));
        String rules = Files.readString(Path.of("deploy/k8s/base/agent-alerts.yaml"));
        String kustomization = Files.readString(Path.of("deploy/overlays/l5/kustomization.yaml"));
        String networkPolicies = Files.readString(Path.of("deploy/k8s/base/network-policies.yaml"));

        assertFalse(Files.exists(Path.of("deploy/overlays/l5/kube-state-metrics.yaml")));
        assertFalse(Files.exists(Path.of("deploy/overlays/l5/kube-state-metrics-egress.yaml")));
        assertFalse(kustomization.contains("kube-state-metrics"));
        assertFalse(kustomization.contains("myai-postgres-backup"));
        assertFalse(foundation.contains("kube-state-metrics"));
        assertFalse(foundation.contains("myai-backup-config"));
        assertFalse(foundation.contains("myai-postgres-backups"));
        assertFalse(networkPolicies.contains("component: backup"));
        assertFalse(networkPolicies.contains("myai-backup-egress"));
        assertFalse(networkPolicies.contains("kube-state-metrics"));
        assertFalse(rules.contains("AgentBackupStale"));
        assertFalse(rules.contains("AgentKubeStateMetricsDown"));
    }

    @Test
    void traceStorageIsPersistentPrivateAndQueryableThroughGrafana() throws Exception {
        String tempo = Files.readString(Path.of("deploy/k8s/base/tempo.yaml"));
        String grafana = Files.readString(Path.of("deploy/k8s/base/grafana.yaml"));
        String foundation = Files.readString(Path.of("deploy/k8s/base/l5-foundation.yaml"));
        String networkPolicies = Files.readString(Path.of("deploy/k8s/base/network-policies.yaml"));
        String kustomization = Files.readString(Path.of("deploy/k8s/kustomization.yaml"));

        assertTrue(kustomization.contains("base/tempo.yaml"));
        assertTrue(kustomization.contains("base/grafana.yaml"));
        assertTrue(tempo.contains("grafana/tempo:2.10.10"));
        assertTrue(tempo.contains("linux/amd64 manifest sha256:2cd56a993318ae307f58289ebc89ae6cb5edc7e82c069371fd5225c393aca867"));
        assertTrue(tempo.contains("--target=all"));
        assertTrue(tempo.contains("type: Recreate"));
        assertTrue(tempo.contains("backend: local"));
        assertTrue(tempo.contains("block_retention: 168h"));
        assertTrue(tempo.contains("name: myai-tempo-data"));
        assertTrue(tempo.contains("storage: 10Gi"));
        assertTrue(tempo.contains("type: ClusterIP"));
        assertTrue(grafana.contains("grafana/grafana:12.4.15"));
        assertTrue(grafana.contains("linux/amd64 manifest sha256:e8e2751f6d838529b13c5f876791404d0e2c35171f9638ee3ae56835e34226a9"));
        assertTrue(grafana.contains("type: Recreate"));
        assertTrue(grafana.contains("name: GF_AUTH_ANONYMOUS_ENABLED"));
        assertTrue(grafana.contains("value: \"false\""));
        assertTrue(grafana.contains("name: GF_PLUGINS_PREINSTALL_DISABLED"));
        assertTrue(grafana.contains("name: GF_PLUGINS_CHECK_FOR_PLUGIN_UPDATES"));
        assertTrue(grafana.contains("name: GF_PLUGINS_PUBLIC_KEY_RETRIEVAL_DISABLED"));
        assertTrue(grafana.contains("secretName: myai-grafana-admin"));
        assertTrue(grafana.contains("url: http://myai-tempo:3200"));
        assertFalse(grafana.contains("kind: Secret"), "Credentials must not be committed with deployment manifests");
        assertTrue(foundation.contains("otlphttp/tempo"));
        assertTrue(foundation.contains("endpoint: http://myai-tempo:4318"));
        assertTrue(foundation.contains("max_elapsed_time: 1h"));
        assertTrue(foundation.contains("key: deployment.environment.name"));
        assertTrue(foundation.contains("value: l5"));
        assertTrue(foundation.contains("processors: [memory_limiter, resource/l5, batch]"));
        assertTrue(foundation.split("environment: l5", -1).length - 1 == 2);
        assertTrue(foundation.contains("exporters: [otlphttp/tempo]"));
        assertFalse(foundation.contains("exporters: [debug]"));
        assertTrue(networkPolicies.contains("myai-tempo-ingress"));
        assertTrue(networkPolicies.contains("myai-grafana-egress"));
    }

    @Test
    void grafanaProvisionsPrometheusBackedAgentRuntimeDashboard() throws Exception {
        String grafana = Files.readString(Path.of("deploy/k8s/base/grafana.yaml"));
        String kustomization = Files.readString(Path.of("deploy/k8s/kustomization.yaml"));
        String networkPolicies = Files.readString(Path.of("deploy/k8s/base/network-policies.yaml"));
        String dashboard = Files.readString(Path.of("deploy/k8s/base/grafana/myai-runtime-overview.json"));
        JsonNode root = new ObjectMapper().readTree(dashboard);

        assertTrue(kustomization.contains("base/grafana/dashboards.yaml"));
        assertTrue(kustomization.contains("base/grafana/myai-runtime-overview.json"));
        assertTrue(grafana.contains("myai-grafana-dashboard-provider"));
        assertTrue(grafana.contains("myai-grafana-dashboard-config"));
        assertTrue(grafana.contains("myai-prometheus"));
        int policyStart = networkPolicies.indexOf("name: myai-grafana-egress");
        int policyEnd = networkPolicies.indexOf("\n---", policyStart);
        String grafanaEgressPolicy = networkPolicies.substring(policyStart, policyEnd);
        assertTrue(grafanaEgressPolicy.contains("app.kubernetes.io/name: myai-prometheus"));
        assertTrue(grafanaEgressPolicy.contains("port: 9090"));
        int prometheusIngressStart = networkPolicies.indexOf("name: myai-prometheus-ingress");
        int prometheusIngressEnd = networkPolicies.indexOf("\n---", prometheusIngressStart);
        String prometheusIngressPolicy = networkPolicies.substring(prometheusIngressStart, prometheusIngressEnd);
        assertTrue(prometheusIngressPolicy.contains("app.kubernetes.io/name: myai-grafana"));
        assertTrue(prometheusIngressPolicy.contains("port: 9090"));
        assertTrue(root.path("uid").asText().equals("myai-runtime-overview"));
        assertTrue(root.path("panels").size() >= 7);

        String dashboardJson = root.toString();
        assertTrue(dashboardJson.contains("myai_agent_worker_queue_depth_runs"));
        assertTrue(dashboardJson.contains("myai_agent_worker_queue_oldest_seconds"));
        assertTrue(dashboardJson.contains("myai_agent_worker_lease_expired_leases"));
        assertTrue(dashboardJson.contains("myai_agent_worker_run_failed_recent_runs"));
        assertTrue(dashboardJson.contains("myai_hikaricp_connections_active"));
        assertTrue(dashboardJson.contains("myai-tempo"));
        for (JsonNode panel : root.path("panels")) {
            assertTrue(panel.path("targets").get(0).path("expr").asText().contains("environment=\"l5\""));
        }
    }

    @Test
    void prometheusDeploymentAndRuleTestsShareTheSameCompleteAlertFile() throws Exception {
        String kustomization = Files.readString(Path.of("deploy/k8s/kustomization.yaml"));
        String foundation = Files.readString(Path.of("deploy/k8s/base/l5-foundation.yaml"));
        String rules = Files.readString(Path.of("deploy/k8s/base/agent-alerts.yaml"));
        String ruleTests = Files.readString(Path.of("ops/monitoring/agent-alerts-test.yaml"));

        assertTrue(kustomization.contains("configMapGenerator:"));
        assertTrue(kustomization.contains("alerts.yml=base/agent-alerts.yaml"));
        assertTrue(foundation.contains("/etc/prometheus/rules/alerts.yml"));
        assertTrue(foundation.contains("mountPath: /etc/prometheus/rules"));
        assertTrue(foundation.contains("name: myai-prometheus-rules"));
        assertFalse(foundation.contains("alerts.yml: |"));
        assertTrue(ruleTests.contains("agent-alerts.yaml"));
        assertTrue(rules.contains("MyAiCollectorTargetDown"));
        assertTrue(rules.contains("MyAiTempoTargetDown"));
        assertTrue(rules.contains("up{job=\"myai-tempo\"} == 0"));
        assertTrue(rules.contains("AgentWorkerQueueStalled"));
        assertTrue(rules.contains("AgentExpiredRunLeases"));
        assertTrue(rules.contains("AgentRecentRunFailures"));
        assertTrue(rules.contains("AgentDatabasePoolNearCapacity"));
        assertFalse(rules.contains("AgentBackupStale"));
        assertFalse(rules.contains("AgentKubeStateMetricsDown"));
    }

    @Test
    void prometheusConsoleUsesCurrentExportedMetricsAndConfiguredDirectories() throws Exception {
        String manifest = Files.readString(Path.of("deploy/k8s/base/l5-foundation.yaml"));
        String console = Files.readString(Path.of("deploy/k8s/base/prometheus-console.yaml"));

        assertTrue(manifest.contains("--web.console.templates=/etc/prometheus/consoles"));
        assertTrue(manifest.contains("--web.console.libraries=/etc/prometheus/consoles"));
        assertTrue(manifest.contains("name: myai-agent-overview"));
        assertTrue(manifest.contains("mountPath: /etc/prometheus/consoles"));
        assertTrue(console.contains("name: myai-agent-overview"));
        assertTrue(console.contains("max(myai_agent_worker_queue_depth_runs)"));
        assertTrue(console.contains("max(myai_agent_worker_queue_oldest_seconds)"));
        assertTrue(console.contains("max(myai_agent_worker_lease_expired_leases)"));
        assertTrue(console.contains("max(myai_agent_worker_run_failed_recent_runs)"));
        assertTrue(console.contains("myai_hikaricp_connections_active"));
    }

    @Test
    void myaiNamespaceIngressIsDefaultDeniedAndOnlyKnownServiceFlowsAreAllowed() throws Exception {
        String policies = Files.readString(Path.of("deploy/k8s/base/network-policies.yaml"));
        String kustomization = Files.readString(Path.of("deploy/k8s/kustomization.yaml"));
        String taskJob = Files.readString(Path.of("deploy/k8s/base/agent-task-job.yaml"));

        assertTrue(kustomization.contains("base/network-policies.yaml"));
        assertTrue(policies.contains("name: myai-default-deny-ingress"));
        assertTrue(policies.contains("policyTypes: [Ingress]"));
        assertTrue(policies.contains("name: myai-postgres-ingress"));
        assertTrue(policies.contains("port: 5432"));
        assertTrue(policies.contains("component: worker"));
        assertTrue(policies.contains("component: task"));
        assertFalse(policies.contains("component: backup"));
        assertTrue(policies.contains("name: myai-collector-ingress"));
        assertTrue(policies.contains("port: 4317"));
        assertTrue(policies.contains("port: 4318"));
        assertTrue(policies.contains("port: 9464"));
        assertTrue(policies.contains("name: myai-prometheus-ingress"));
        assertTrue(policies.contains("port: 9090"));
        assertTrue(policies.contains("name: myai-default-deny-egress"));
        assertTrue(policies.contains("name: myai-agent-egress"));
        assertTrue(policies.contains("name: myai-egress-proxy-egress"));
        assertTrue(policies.contains("port: 3128"));
        assertTrue(policies.contains("port: 443"));
        assertTrue(taskJob.contains("app.kubernetes.io/component: task"));
        assertTrue(taskJob.contains("name: AGENT_MODEL_PROXY_URL"));
        String worker = Files.readString(Path.of("deploy/k8s/base/agent-worker-deployment.yaml"));
        assertTrue(worker.contains("name: AGENT_MODEL_PROXY_URL"));
        String proxy = Files.readString(Path.of("deploy/k8s/base/model-egress-proxy.yaml"));
        String proxyAcl = Files.readString(Path.of("ops/squid-model-egress.conf"));
        assertTrue(proxy.contains("registry.example.invalid/myai-model-egress-proxy"));
        assertTrue(proxyAcl.contains("api.deepseek.com"));
        assertTrue(proxyAcl.contains("acl deepseek_api dstdomain api.deepseek.com"));
        assertFalse(proxyAcl.contains("dstdomain .api.deepseek.com"),
                "A leading dot would widen the allow-list to provider subdomains");
        assertTrue(proxyAcl.contains("http_access deny all"));
        assertTrue(policies.contains("cidr: 0.0.0.0/0"));
        assertTrue(policies.contains("              - 10.0.0.0/8"));
        assertTrue(policies.contains("- 192.168.0.0/16"));
        assertTrue(policies.contains("name: myai-agent-egress\n"));
    }

    @Test
    void githubActionsDependenciesArePinnedToFullCommitShasAndAutoUpdated() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));
        Path dependabotPath = Path.of(".github/dependabot.yml");
        String dependabot = Files.exists(dependabotPath) ? Files.readString(dependabotPath) : "";
        long actionReferences = workflow.lines()
                .filter(line -> line.stripLeading().startsWith("uses:"))
                .count();
        long pinnedActionReferences = workflow.lines()
                .filter(line -> line.matches("\\s*uses:\\s+[^@\\s]+@[0-9a-f]{40}\\s+#\\s+v[0-9.]+\\s*"))
                .count();

        assertTrue(actionReferences > 0);
        assertTrue(actionReferences == pinnedActionReferences,
                "Every GitHub Action reference must use a full commit SHA and a release comment");
        assertTrue(dependabot.contains("package-ecosystem: github-actions"));
        assertTrue(dependabot.contains("interval: weekly"));
        assertTrue(workflow.contains("persist-credentials: false"),
                "Checkout must not persist the workflow token in the repository config");
        assertTrue(workflow.contains("ACTIONLINT_VERSION: 1.7.12"));
        assertTrue(workflow.contains("ACTIONLINT_SHA256: 8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"),
                "Workflow lint tool must be pinned by an independently verified archive checksum");
        assertTrue(workflow.contains("${temp_dir}/actionlint\" -color"),
                "CI must lint the workflow source before application build and publish jobs");
    }

    @Test
    void containerImagesArePublishedOnlyAfterVerificationAndVulnerabilityScans() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));

        assertTrue(workflow.contains("publish-images:"));
        assertTrue(workflow.contains("needs: verify"));
        assertTrue(workflow.contains("github.event_name == 'push'"));
        assertTrue(workflow.contains("github.event.repository.default_branch"));
        assertTrue(workflow.contains("packages: write"));
        assertTrue(workflow.contains("ghcr.io/"));
        assertTrue(workflow.contains("docker push"));
        assertTrue(workflow.contains("GITHUB_SHA"));
        assertTrue(workflow.contains("org.opencontainers.image.source"));
        assertTrue(workflow.contains("org.opencontainers.image.revision"));
        assertFalse(workflow.contains(":latest"));
        String publishJob = workflow.substring(workflow.indexOf("publish-images:"));
        assertTrue(publishJob.indexOf("Scan runtime image") < publishJob.lastIndexOf("docker push"));
        assertTrue(publishJob.indexOf("Scan model egress proxy image") < publishJob.lastIndexOf("docker push"));
        assertFalse(workflow.contains("backup"));
        assertFalse(workflow.contains("kube-state-metrics"));
    }

    @Test
    void backupAndRestoreAutomationAreAbsentFromSourceAndCi() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));
        assertFalse(Files.exists(Path.of("ops/postgres-backup.sh")));
        assertFalse(Files.exists(Path.of("ops/postgres-restore.sh")));
        assertFalse(Files.exists(Path.of("ops/backup.Dockerfile")));
        assertFalse(Files.exists(Path.of("deploy/k8s/base/postgres-backup-cronjob.yaml")));
        assertFalse(workflow.contains("myai-postgres-backup"));
        assertFalse(workflow.contains("postgres-backup"));
        assertFalse(workflow.contains("fetch-age.sh"));
    }

    private static void assertProviderCredentialIsMountedAsReadOnlyFile(String manifest) {
        assertFalse(manifest.contains("name: DEEPSEEK_API_KEY"));
        assertTrue(manifest.contains("name: DEEPSEEK_KEY_FILE"));
        assertTrue(manifest.contains("value: /var/run/secrets/myai/deepseek-api-key"));
        assertTrue(manifest.contains("name: DEEPSEEK_BASE_URL"));
        assertTrue(manifest.contains("key: model-base-url"));
        assertTrue(manifest.contains("fsGroup: 10001"));
        assertTrue(manifest.contains("name: provider-key"));
        assertTrue(manifest.contains("secretName: myai-provider"));
        assertTrue(manifest.contains("defaultMode: 0440"));
        assertTrue(manifest.contains("mountPath: /var/run/secrets/myai"));
        assertTrue(manifest.contains("readOnly: true"));
    }
}
