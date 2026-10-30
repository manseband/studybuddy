package studybuddy.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import studybuddy.domain.Chunk;

/**
 * Deterministic keyword retrieval using the BM25 ranking formula. Needs no API key, so it is the
 * fallback when Gemini is not configured and the retriever used in tests.
 *
 * termIndex maps each term to the ids of the chunks containing it (an inverted index), so only
 * chunks sharing at least one term with the query are scored. A chunk with no matching term is
 * never returned (that is the relevance threshold for this strategy).
 */
public class KeywordRetriever implements RetrievalStrategy {

    /** BM25 term-frequency saturation. */
    private static final double K1 = 1.2;
    /** BM25 length normalization. */
    private static final double B = 0.75;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from", "how", "in",
            "into", "is", "it", "its", "of", "on", "or", "that", "the", "their", "then", "there", "these",
            "this", "to", "was", "what", "when", "where", "which", "who", "why", "will", "with", "you", "your");

    private final Map<String, Set<Long>> termIndex = new HashMap<>();
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final Map<Long, Map<String, Integer>> termCounts = new HashMap<>();
    private final Map<Long, Integer> lengths = new HashMap<>();

    @Override
    public synchronized void index(List<Chunk> newChunks) {
        for (Chunk chunk : newChunks) {
            if (chunk.getId() == 0) {
                throw new IllegalArgumentException("Chunks must be saved before they are indexed");
            }
            removeChunk(chunk.getId());
            List<String> terms = tokenize(chunk.getText());
            Map<String, Integer> counts = new HashMap<>();
            for (String term : terms) {
                counts.merge(term, 1, Integer::sum);
            }
            chunks.put(chunk.getId(), chunk);
            termCounts.put(chunk.getId(), counts);
            lengths.put(chunk.getId(), terms.size());
            for (String term : counts.keySet()) {
                termIndex.computeIfAbsent(term, t -> new HashSet<>()).add(chunk.getId());
            }
        }
    }

    @Override
    public synchronized List<Chunk> retrieve(long courseId, String query, int k) {
        Set<String> queryTerms = new HashSet<>(tokenize(query));
        if (queryTerms.isEmpty() || k <= 0) {
            return List.of();
        }
        List<Long> courseChunkIds = chunks.values().stream()
                .filter(c -> c.getCourseId() == courseId).map(Chunk::getId).toList();
        if (courseChunkIds.isEmpty()) {
            return List.of();
        }
        Set<Long> inCourse = new HashSet<>(courseChunkIds);
        double avgLength = courseChunkIds.stream().mapToInt(lengths::get).average().orElse(1);
        int n = courseChunkIds.size();

        Map<Long, Double> scores = new LinkedHashMap<>();
        for (String term : queryTerms) {
            List<Long> matching = termIndex.getOrDefault(term, Set.of()).stream().filter(inCourse::contains).toList();
            if (matching.isEmpty()) {
                continue;
            }
            double idf = Math.log(1 + (n - matching.size() + 0.5) / (matching.size() + 0.5));
            for (long id : matching) {
                int tf = termCounts.get(id).get(term);
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths.get(id) / avgLength));
                scores.merge(id, idf * norm, Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(k)
                .map(e -> chunks.get(e.getKey()))
                .toList();
    }

    @Override
    public synchronized void removeMaterial(long materialId) {
        List<Long> ids = chunks.values().stream()
                .filter(c -> c.getMaterialId() == materialId).map(Chunk::getId).toList();
        ids.forEach(this::removeChunk);
    }

    public synchronized int size() {
        return chunks.size();
    }

    private void removeChunk(long chunkId) {
        Map<String, Integer> counts = termCounts.remove(chunkId);
        if (counts != null) {
            for (String term : counts.keySet()) {
                Set<Long> ids = termIndex.get(term);
                ids.remove(chunkId);
                if (ids.isEmpty()) {
                    termIndex.remove(term);
                }
            }
        }
        chunks.remove(chunkId);
        lengths.remove(chunkId);
    }

    /** Lower-case words of 2+ letters/digits, without stop words, with a simple plural "s" removed. */
    static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null) {
            return terms;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() < 2 || STOP_WORDS.contains(word)) {
                continue;
            }
            if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
                word = word.substring(0, word.length() - 1);
            }
            terms.add(word);
        }
        return terms;
    }
}
