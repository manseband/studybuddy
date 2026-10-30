package studybuddy.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import studybuddy.domain.Chunk;

class KeywordRetrieverTest {

    private KeywordRetriever retriever;

    private static Chunk chunk(long id, long materialId, long courseId, String text) {
        return new Chunk(id, materialId, courseId, 0, text, 1);
    }

    @BeforeEach
    void setUp() {
        retriever = new KeywordRetriever();
        retriever.index(List.of(
                chunk(1, 10, 1, "The Facade pattern provides a simple interface to a complex subsystem."),
                chunk(2, 10, 1, "The Adapter pattern converts the interface of a class into another interface."),
                chunk(3, 11, 1, "Observer: subjects notify registered observers when their state changes."),
                chunk(4, 12, 2, "Facade appears in another course and must not leak into course 1.")));
    }

    @Test
    void ranksTheMostRelevantChunkFirst() {
        List<Chunk> results = retriever.retrieve(1, "How do observers get notified?", 3);

        assertEquals(1, results.size());
        assertEquals(3, results.get(0).getId());
    }

    @Test
    void repeatedRareTermsWin() {
        List<Chunk> results = retriever.retrieve(1, "adapter interface", 2);

        assertEquals(2, results.get(0).getId());
        assertEquals(2, results.size());
    }

    @Test
    void onlySearchesTheRequestedCourse() {
        List<Chunk> results = retriever.retrieve(1, "facade", 5);

        assertEquals(List.of(1L), results.stream().map(Chunk::getId).toList());
        assertEquals(4, retriever.retrieve(2, "facade", 5).get(0).getId());
    }

    @Test
    void returnsNothingWhenNoTermMatches() {
        assertTrue(retriever.retrieve(1, "quantum chromodynamics", 6).isEmpty());
        assertTrue(retriever.retrieve(1, "the of and", 6).isEmpty());
        assertTrue(retriever.retrieve(99, "facade", 6).isEmpty());
    }

    @Test
    void removedMaterialsAreNoLongerFound() {
        retriever.removeMaterial(10);

        assertTrue(retriever.retrieve(1, "facade adapter", 6).isEmpty());
        assertEquals(2, retriever.size());
    }

    @Test
    void reindexingAChunkReplacesItsOldText() {
        retriever.index(List.of(chunk(3, 11, 1, "Strategy lets an algorithm vary independently.")));

        assertTrue(retriever.retrieve(1, "observer", 6).isEmpty());
        assertEquals(3, retriever.retrieve(1, "strategy algorithm", 6).get(0).getId());
    }

    @Test
    void unsavedChunksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> retriever.index(List.of(chunk(0, 1, 1, "text"))));
    }

    @Test
    void tokenizerDropsStopWordsAndPlurals() {
        assertEquals(List.of("observer", "notify", "subject"), KeywordRetriever.tokenize("The Observers notify subjects!"));
        assertEquals(List.of("class"), KeywordRetriever.tokenize("class"));
    }
}
