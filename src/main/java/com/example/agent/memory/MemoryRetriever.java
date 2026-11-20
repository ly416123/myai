package com.example.agent.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded and deterministic lexical retrieval without embeddings or an external search service. */
public final class MemoryRetriever {
    public static final String POLICY_VERSION = "policy-v1";
    private final int maxEntries;
    private final int maxCharacters;

    public MemoryRetriever(int maxEntries, int maxCharacters) {
        if (maxEntries < 1 || maxEntries > 5 || maxCharacters < 1 || maxCharacters > 2_000) {
            throw new IllegalArgumentException("Memory retrieval budget is outside the supported range");
        }
        this.maxEntries = maxEntries;
        this.maxCharacters = maxCharacters;
    }

    public MemoryContextSnapshot retrieve(String query, List<MemoryEntry> entries) {
        if (query == null || entries == null) throw new IllegalArgumentException("Query and entries are required");
        String normalizedQuery = normalize(query);
        Set<String> queryTerms = terms(normalizedQuery);
        List<ScoredEntry> ranked = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            String normalizedKey = normalize(entry.key());
            boolean exactKey = !normalizedKey.isEmpty() && normalizedQuery.contains(normalizedKey);
            double score = overlap(queryTerms, terms(normalize(entry.key() + " " + entry.content())));
            if (exactKey || score > 0) ranked.add(new ScoredEntry(entry, exactKey, score));
        }
        ranked.sort(Comparator.<ScoredEntry>comparingInt(item -> item.exactKey() ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .thenComparing(item -> item.entry().memoryId()));

        List<MemoryEntry> selected = new ArrayList<>();
        for (ScoredEntry candidate : ranked) {
            if (selected.size() == maxEntries) break;
            List<MemoryEntry> proposed = new ArrayList<>(selected);
            proposed.add(candidate.entry());
            int codePoints = codePointCount(MemoryPrompt.renderEntries(proposed));
            if (codePoints <= maxCharacters) selected.add(candidate.entry());
        }
        int characters = codePointCount(MemoryPrompt.renderEntries(selected));
        return new MemoryContextSnapshot(false, POLICY_VERSION, selected, characters);
    }

    private static double overlap(Set<String> query, Set<String> candidate) {
        if (query.isEmpty() || candidate.isEmpty()) return 0;
        int common = 0;
        for (String term : query) if (candidate.contains(term)) common++;
        return (double) common / (query.size() + candidate.size() - common);
    }

    private static Set<String> terms(String value) {
        Set<String> result = new HashSet<>();
        int[] points = value.codePoints().toArray();
        for (int i = 0; i < points.length;) {
            if (Character.UnicodeScript.of(points[i]) == Character.UnicodeScript.HAN) {
                int end = i + 1;
                while (end < points.length && Character.UnicodeScript.of(points[end])
                        == Character.UnicodeScript.HAN) end++;
                if (end - i == 1) result.add(new String(points, i, 1));
                else for (int j = i; j < end - 1; j++) result.add(new String(points, j, 2));
                i = end;
            } else if (Character.isLetterOrDigit(points[i])) {
                int end = i + 1;
                while (end < points.length && Character.isLetterOrDigit(points[end])
                        && Character.UnicodeScript.of(points[end]) != Character.UnicodeScript.HAN) end++;
                result.add(new String(points, i, end - i));
                i = end;
            } else i++;
        }
        return result;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static int codePointCount(String text) {
        return text.codePointCount(0, text.length());
    }

    private record ScoredEntry(MemoryEntry entry, boolean exactKey, double score) { }
}
