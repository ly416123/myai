package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Exports only persisted metadata and scores; prompts, answers, and tool arguments are absent by construction. */
public final class EvaluationJsonExporter {
    private final ObjectMapper mapper;
    public EvaluationJsonExporter(ObjectMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper).copy()
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }
    public Path export(EvaluationSnapshot snapshot, Path target) {
        if (snapshot == null || target == null) throw new IllegalArgumentException("Evaluation and target are required");
        try {
            Path absolute = target.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
            try { Files.createFile(absolute, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException ignored) { Files.createFile(absolute); }
            Files.write(absolute, bytes);
            return absolute;
        } catch (IOException e) { throw new IllegalStateException("Unable to export evaluation report"); }
    }
}
