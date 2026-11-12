package com.example.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 本地 JSON 检查点存储；每个 run 独立文件并以原子替换写入。 */
public final class JsonFileRunStore implements RunStore {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private final Path directory;
    private final ObjectMapper mapper;

    public JsonFileRunStore(Path directory, ObjectMapper mapper) {
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override public synchronized void save(RunRecord run) {
        Path target = pathFor(run.runId());
        Path temp = directory.resolve("." + run.runId() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(directory);
            setPermissions(directory, DIRECTORY_PERMISSIONS);
            byte[] data = mapper.writeValueAsBytes(run);
            Files.write(temp, data);
            setPermissions(temp, FILE_PERMISSIONS);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissions(target, FILE_PERMISSIONS);
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw new IllegalStateException("Unable to persist run checkpoint", e);
        }
    }

    @Override public synchronized Optional<RunRecord> find(String runId) {
        Path path = pathFor(runId);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try { return Optional.of(mapper.readValue(path.toFile(), RunRecord.class)); }
        catch (IOException e) { throw new IllegalStateException("Unable to read run checkpoint", e); }
    }

    @Override public synchronized List<RunRecord> list() {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> readFile(path))
                    .sorted(Comparator.comparing(RunRecord::createdAt)).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list run checkpoints", e);
        }
    }

    private RunRecord readFile(Path path) {
        try { return mapper.readValue(path.toFile(), RunRecord.class); }
        catch (IOException e) { throw new IllegalStateException("Unable to read run checkpoint", e); }
    }

    private Path pathFor(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Invalid run ID");
        }
        Path path = directory.resolve(runId + ".json").normalize();
        if (!path.getParent().equals(directory)) throw new IllegalArgumentException("Invalid run ID");
        return path;
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try { Files.setPosixFilePermissions(path, permissions); }
        catch (UnsupportedOperationException | IOException ignored) { /* 非 POSIX 文件系统使用系统默认权限。 */ }
    }
}
