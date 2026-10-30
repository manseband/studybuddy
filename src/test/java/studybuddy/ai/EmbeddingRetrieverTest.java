package studybuddy.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import studybuddy.domain.Chunk;

class EmbeddingRetrieverTest {

    /** Toy embedding: one dimension per known word, counting occurrences. No network. */
    static class WordCountEmbeddingModel implements EmbeddingModel {
        static final List<String> VOCAB = List.of("facade", "adapter", "observer", "interface", "notify", "subsystem");
        int calls;
        int textsEmbedded;
        boolean failing;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls++;
            if (failing) {
                throw new RuntimeException("service unavailable");
            }
            List<Embedding> result = new ArrayList<>();
            for (TextSegment segment : segments) {
                textsEmbedded++;
                float[] v = new float[VOCAB.size()];
                for (String word : segment.text().toLowerCase(Locale.ROOT).split("\\W+")) {
                    int i = VOCAB.indexOf(word);
                    if (i >= 0) {
                        v[i]++;
                    }
                }
                result.add(Embedding.from(v));
            }
            return Response.from(result);
        }
    }

    @BeforeAll
    static void quietLogs() {
        Logger.getLogger(EmbeddingRetriever.class.getName()).setLevel(Level.OFF);
    }

    private static List<Chunk> sampleChunks() {
        return List.of(
                new Chunk(1, 10, 1, 0, "Facade gives one interface to a subsystem", 1),
                new Chunk(2, 10, 1, 1, "Adapter converts one interface to another interface", 2),
                new Chunk(3, 11, 1, 0, "Observer subjects notify observer objects", 1));
    }

    @Test
    void ranksBySimilarityAndCachesVectorsWithModelName() {
        WordCountEmbeddingModel model = new WordCountEmbeddingModel();
        EmbeddingRetriever retriever = new EmbeddingRetriever(model, "toy-model", 0.3);
        List<Chunk> chunks = sampleChunks();

        retriever.index(chunks);

        assertTrue(chunks.get(0).hasEmbeddingFrom("toy-model"));
        List<Chunk> results = retriever.retrieve(1, "notify observer", 3);
        assertEquals(3, results.get(0).getId());
        assertEquals(1, results.size(), "chunks below the similarity threshold are dropped");
    }

    @Test
    void doesNotReEmbedChunksThatAlreadyHaveVectors() {
        WordCountEmbeddingModel model = new WordCountEmbeddingModel();
        List<Chunk> chunks = sampleChunks();
        new EmbeddingRetriever(model, "toy-model", 0.3).index(chunks);
        int embeddedBefore = model.textsEmbedded;

        new EmbeddingRetriever(model, "toy-model", 0.3).index(chunks);

        assertEquals(embeddedBefore, model.textsEmbedded);
    }

    @Test
    void vectorsFromAnotherModelAreRecomputed() {
        Chunk chunk = new Chunk(1, 10, 1, 0, "Facade", 1);
        chunk.setEmbedding(new float[] {1f}, "old-model");

        new EmbeddingRetriever(new WordCountEmbeddingModel(), "toy-model", 0.3).index(List.of(chunk));

        assertTrue(chunk.hasEmbeddingFrom("toy-model"));
    }

    @Test
    void fallsBackToKeywordSearchWhenEmbeddingFails() {
        WordCountEmbeddingModel model = new WordCountEmbeddingModel();
        model.failing = true;
        EmbeddingRetriever retriever = new EmbeddingRetriever(model, "toy-model", 0.3);
        List<Chunk> chunks = sampleChunks();

        retriever.index(chunks);

        assertNull(chunks.get(0).getEmbedding());
        List<Chunk> results = retriever.retrieve(1, "adapter", 3);
        assertEquals(2, results.get(0).getId());
    }

    @Test
    void fallsBackWhenTheQueryCannotBeEmbedded() {
        WordCountEmbeddingModel model = new WordCountEmbeddingModel();
        EmbeddingRetriever retriever = new EmbeddingRetriever(model, "toy-model", 0.3);
        retriever.index(sampleChunks());
        model.failing = true;

        assertEquals(1, retriever.retrieve(1, "facade", 3).get(0).getId());
    }

    @Test
    void removedMaterialsDisappear() {
        EmbeddingRetriever retriever = new EmbeddingRetriever(new WordCountEmbeddingModel(), "toy-model", 0.1);
        retriever.index(sampleChunks());

        retriever.removeMaterial(10);

        List<Chunk> results = retriever.retrieve(1, "facade adapter interface", 5);
        assertTrue(results.stream().noneMatch(c -> c.getMaterialId() == 10));
        assertNotNull(retriever.retrieve(1, "observer", 5));
    }

    @Test
    void cosineOfOrthogonalAndEqualVectors() {
        assertEquals(1.0, EmbeddingRetriever.cosine(new float[] {1, 2}, new float[] {2, 4}), 1e-9);
        assertEquals(0.0, EmbeddingRetriever.cosine(new float[] {1, 0}, new float[] {0, 1}), 1e-9);
        assertEquals(0.0, EmbeddingRetriever.cosine(new float[] {0, 0}, new float[] {1, 1}), 1e-9);
    }
}
