package studybuddy.ai;

import java.util.List;

import studybuddy.domain.Chunk;

/**
 * Strategy for finding the course excerpts most relevant to a query (F04 and every retrieval-based
 * feature). EmbeddingRetriever ranks by vector similarity when an API key is configured;
 * KeywordRetriever ranks by keyword overlap (BM25) otherwise and in tests.
 */
public interface RetrievalStrategy {

    /** Adds chunks to the index. Chunks must already have their database ids. */
    void index(List<Chunk> chunks);

    /**
     * Returns at most k chunks of the course, most relevant first. Chunks below the strategy's
     * relevance threshold are left out, so the list may be empty when nothing matches.
     */
    List<Chunk> retrieve(long courseId, String query, int k);

    /** Removes every chunk of a deleted material from the index. */
    void removeMaterial(long materialId);
}
