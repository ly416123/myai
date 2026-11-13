package com.example.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerDeploymentContractTest {
    @Test
    void imageBuildCannotCopyLocalCredentialsAndRunsAsNonRoot() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String ignore = Files.readString(Path.of(".dockerignore"));

        assertTrue(dockerfile.contains("USER 10001:10001"));
        assertTrue(dockerfile.contains("COPY target/minimal-agent-runtime-1.0-SNAPSHOT.jar /app/app.jar"));
        assertTrue(dockerfile.contains("COPY eval/scenarios-v1.json /app/eval/scenarios-v1.json"));
        assertFalse(dockerfile.contains("COPY . ."));
        assertTrue(ignore.contains("deepseek.key"));
        assertTrue(ignore.contains(".env"));
        assertTrue(ignore.contains("*.dump.age"));
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
        assertTrue(overlay.contains("newName: docker.io/library/myai-postgres-backup"));
        assertTrue(Files.readString(Path.of("deploy/k8s/base/postgres-backup-cronjob.yaml"))
                .contains("image: docker.io/library/myai-postgres-backup:l5-20261113"));
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
        String rules = Files.readString(Path.of("ops/monitoring/agent-alerts.yaml"));
        String deployed = Files.readString(Path.of("deploy/k8s/base/l5-foundation.yaml"));

        assertTrue(rules.contains("AgentWorkerQueueStalled"));
        assertTrue(rules.contains("myai_agent_worker_queue_oldest_seconds"));
        assertTrue(rules.contains("AgentExpiredRunLeases"));
        assertTrue(rules.contains("myai_agent_worker_lease_expired_leases"));
        assertTrue(rules.contains("AgentRecentRunFailures"));
        assertTrue(rules.contains("myai_agent_worker_run_failed_recent_runs"));
        assertTrue(rules.contains("myai_hikaricp_connections_active"));
        assertTrue(deployed.contains("myai_agent_worker_queue_oldest_seconds"));
        assertTrue(deployed.contains("myai_agent_worker_lease_expired_leases"));
        assertTrue(deployed.contains("myai_agent_worker_run_failed_recent_runs"));
        assertTrue(deployed.contains("myai_hikaricp_connections_active"));
        assertTrue(Files.readString(Path.of("src/test/resources/otel/collector-config.yaml"))
                .contains("namespace: myai"));
        assertFalse(rules.contains("run_id"));
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
        assertTrue(publishJob.indexOf("Scan backup image") < publishJob.lastIndexOf("docker push"));
    }

    @Test
    void scheduledBackupIsEncryptedBoundedAndUsesClusterSecretsAndStorage() throws Exception {
        String manifest = Files.readString(Path.of("deploy/k8s/base/postgres-backup-cronjob.yaml"));
        String image = Files.readString(Path.of("ops/backup.Dockerfile"));
        String smoke = Files.readString(Path.of("ops/tests/postgres-backup-restore-smoke.sh"));
        String retention = Files.readString(Path.of("ops/prune-postgres-backups.sh"));
        String scheduled = Files.readString(Path.of("ops/run-scheduled-backup.sh"));

        assertTrue(manifest.contains("kind: CronJob"));
        assertTrue(manifest.contains("schedule: \"0 2 * * *\""));
        assertTrue(manifest.contains("timeZone: Asia/Shanghai"));
        assertTrue(manifest.contains("concurrencyPolicy: Forbid"));
        assertTrue(manifest.contains("activeDeadlineSeconds: 600"));
        assertTrue(manifest.contains("name: myai-database"));
        assertTrue(manifest.contains("key: database-password"));
        assertTrue(manifest.contains("myai-backup-config"));
        assertTrue(manifest.contains("key: age-recipient"));
        assertTrue(manifest.contains("value: verify-full"));
        assertTrue(manifest.contains("PGSSLROOTCERT"));
        assertTrue(manifest.contains("AGENT_BACKUP_RETENTION_DAYS"));
        assertTrue(manifest.contains("persistentVolumeClaim:"));
        assertTrue(manifest.contains("claimName: myai-postgres-backups"));
        assertFalse(manifest.contains("myai-backup-database"));
        assertTrue(manifest.contains("database-ca"));
        assertFalse(manifest.contains("name: myai-registry"));
        assertFalse(manifest.contains("hostPath:"));
        assertTrue(manifest.contains("runAsNonRoot: true"));
        assertTrue(manifest.contains("readOnlyRootFilesystem: true"));
        assertTrue(image.contains("postgres:16-alpine"));
        assertTrue(image.contains("COPY ops/.build/age /usr/local/bin/age"));
        assertTrue(image.contains("COPY ops/prune-postgres-backups.sh /opt/myai/prune-postgres-backups.sh"));
        assertTrue(scheduled.indexOf("/opt/myai/postgres-backup.sh") < scheduled.indexOf("/opt/myai/prune-postgres-backups.sh"));
        assertTrue(image.contains("apk upgrade --no-cache"));
        assertTrue(image.contains("rm -f /usr/local/bin/gosu"));
        assertTrue(image.contains("age"));
        assertFalse(image.contains("apk add"));
        assertTrue(image.contains("USER 10001:10001"));
        assertTrue(smoke.contains("docker exec --interactive"));
        assertFalse(smoke.contains("for tool in docker pg_dump"));
        assertTrue(Files.readString(Path.of("ops/fetch-age.sh")).contains("go_version=\"1.26.11\""));
        assertTrue(Files.readString(Path.of("ops/fetch-age.sh")).contains("6c2f29b458b5add0835f18b776f69c8881a6d88428f1484a6da55755e620757e"));
        assertTrue(retention.contains("myai-${PGDATABASE}-*.dump.age"));
        assertTrue(retention.contains("! -e \"$archive.lock\""));
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
