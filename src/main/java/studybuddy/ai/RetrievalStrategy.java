package studybuddy.ai;

import java.util.List;

import studybuddy.domain.Chunk;

/**
 * Strategy for finding the course excerpts most relevant to a query (F04 and every retrieval-based
 * feature). EmbeddingRetriever ranks by vector similarity when an API key is configured;
 * KeywordRetriever ranks by keyword overlap (BM25) otherwise and in tests.
 */
public interface RetrievalStrategy {

    /**
     * Adds new chunks to the index, computing whatever the strategy needs (e.g. embeddings).
     * Chunks must already have their database ids.
     */
    void index(List<Chunk> chunks);

    /**
     * Adds chunks already stored in the database to the index without computing anything new
     * (no API calls). Used at startup to rebuild the in-memory index.
     */
    void load(List<Chunk> storedChunks);

    /**
     * Returns at most k chunks of the course, most relevant first. Chunks below the strategy's
     * relevance threshold are left out, so the list may be empty when nothing matches.
     */
    List<Chunk> retrieve(long courseId, String query, int k);

    /** Removes every chunk of a deleted material from the index. */
    void removeMaterial(long materialId);
}
