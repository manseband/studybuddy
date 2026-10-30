package studybuddy.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import studybuddy.domain.Chunk;

/**
 * Semantic retrieval: ranks chunks by cosine similarity between the query's embedding and each chunk's
 * cached embedding (F01, F04). Each chunk is embedded once; the vector is stored with the model name, so
 * vectors from another model are ignored and recomputed.
 *
 * If the embedding service fails, or the course has no vectors yet, retrieval falls back to the
 * {@link KeywordRetriever}, so materials stay searchable (F01 "embedding service unavailable").
 */
public class EmbeddingRetriever implements RetrievalStrategy {

    private static final Logger LOG = Logger.getLogger(EmbeddingRetriever.class.getName());
    private static final int BATCH_SIZE = 100;

    private final EmbeddingModel embeddingModel;
    private final String modelName;
    private final double minSimilarity;
    private final KeywordRetriever fallback = new KeywordRetriever();
    private final Map<Long, Chunk> chunks = new HashMap<>();

    public EmbeddingRetriever(EmbeddingModel embeddingModel, String modelName, double minSimilarity) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
        this.minSimilarity = minSimilarity;
    }

    /** Creates a retriever backed by the Gemini embedding API. */
    public static EmbeddingRetriever create(String apiKey, String modelName, Duration timeout, double minSimilarity) {
        EmbeddingModel model = GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .maxRetries(2)
                .build();
        return new EmbeddingRetriever(model, modelName, minSimilarity);
    }

    /**
     * Indexes the chunks for keyword search and requests embeddings for chunks that do not have one from
     * this model yet. New vectors are set on the chunk objects so the caller can save them.
     * A failed embedding request is logged; those chunks are retried the next time they are indexed.
     */
    @Override
    public synchronized void index(List<Chunk> newChunks) {
        fallback.index(newChunks);
        newChunks.forEach(c -> chunks.put(c.getId(), c));
        List<Chunk> missing = newChunks.stream().filter(c -> !c.hasEmbeddingFrom(modelName)).toList();
        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            List<Chunk> batch = missing.subList(start, Math.min(start + BATCH_SIZE, missing.size()));
            try {
                List<Embedding> vectors = embeddingModel
                        .embedAll(batch.stream().map(c -> TextSegment.from(c.getText())).toList())
                        .content();
                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).setEmbedding(vectors.get(i).vector(), modelName);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Embedding request failed; keyword search will be used for these chunks", e);
                return;
            }
        }
    }

    @Override
    public synchronized List<Chunk> retrieve(long courseId, String query, int k) {
        List<Chunk> candidates = chunks.values().stream()
                .filter(c -> c.getCourseId() == courseId && c.hasEmbeddingFrom(modelName))
                .toList();
        if (candidates.isEmpty() || query == null || query.isBlank()) {
            return fallback.retrieve(courseId, query, k);
        }
        float[] queryVector;
        try {
            queryVector = embeddingModel.embedAll(List.of(TextSegment.from(query))).content().get(0).vector();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Query embedding failed; using keyword search", e);
            return fallback.retrieve(courseId, query, k);
        }
        record Scored(Chunk chunk, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Chunk chunk : candidates) {
            double similarity = cosine(queryVector, chunk.getEmbedding());
            if (similarity >= minSimilarity) {
                scored.add(new Scored(chunk, similarity));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(k)
                .map(Scored::chunk)
                .toList();
    }

    @Override
    public synchronized void removeMaterial(long materialId) {
        fallback.removeMaterial(materialId);
        chunks.values().removeIf(c -> c.getMaterialId() == materialId);
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
