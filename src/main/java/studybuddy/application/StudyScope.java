package studybuddy.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import studybuddy.ai.RetrievalStrategy;
import studybuddy.domain.Chunk;
import studybuddy.domain.Concept;
import studybuddy.domain.Material;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.MaterialRepository;

/**
 * Works out which part of a course a quiz or flashcard deck is written from (F05, F06): the chosen
 * materials, the excerpts about the chosen concepts, or else the whole course.
 */
class StudyScope {

    private final MaterialRepository materialRepo;
    private final ConceptRepository conceptRepo;
    private final RetrievalStrategy retriever;
    private final int topK;
    private final int wordBudget;

    /**
     * @param topK       number of excerpts retrieved per concept when the scope is a set of concepts
     * @param wordBudget most words of material text sent in one prompt
     */
    StudyScope(MaterialRepository materialRepo, ConceptRepository conceptRepo, RetrievalStrategy retriever, int topK,
               int wordBudget) {
        this.materialRepo = materialRepo;
        this.conceptRepo = conceptRepo;
        this.retriever = retriever;
        this.topK = topK;
        this.wordBudget = wordBudget;
    }

    /** The chosen concepts, or all of the course's concepts if none were chosen. */
    List<Concept> concepts(long courseId, List<Long> conceptIds) {
        List<Concept> all = conceptRepo.findByCourse(courseId);
        if (conceptIds.isEmpty()) {
            return all;
        }
        List<Concept> chosen = new ArrayList<>();
        for (long id : conceptIds) {
            chosen.add(all.stream().filter(c -> c.getId() == id).findFirst().orElseThrow(
                    () -> new IllegalArgumentException("Concept " + id + " does not belong to this course")));
        }
        return chosen;
    }

    /**
     * The excerpts to write from: chunks in scope, picked at random until the word budget is reached, so
     * that large courses fit in one prompt and repeated requests cover different parts. They stay in
     * document order.
     *
     * @param concepts the result of {@link #concepts}; only searched for when conceptIds is not empty
     * @throws IllegalStateException if the scope contains no text
     */
    List<Chunk> excerpts(long courseId, List<Long> materialIds, List<Long> conceptIds, List<Concept> concepts) {
        List<Chunk> shuffled = new ArrayList<>(chunksInScope(courseId, materialIds, conceptIds, concepts));
        if (shuffled.isEmpty()) {
            throw new IllegalStateException("There is no text to write from. Upload materials first.");
        }
        Collections.shuffle(shuffled);
        List<Chunk> selected = new ArrayList<>();
        int words = 0;
        for (Chunk chunk : shuffled) {
            if (!selected.isEmpty() && words + chunk.wordCount() > wordBudget) {
                break;
            }
            selected.add(chunk);
            words += chunk.wordCount();
        }
        selected.sort(Comparator.comparingLong(Chunk::getMaterialId).thenComparingInt(Chunk::getPosition));
        return selected;
    }

    private List<Chunk> chunksInScope(long courseId, List<Long> materialIds, List<Long> conceptIds,
                                      List<Concept> concepts) {
        if (materialIds.isEmpty() && conceptIds.isEmpty()) {
            return materialRepo.findChunksByCourse(courseId);
        }
        Map<Long, Chunk> chunks = new LinkedHashMap<>();
        for (long id : materialIds) {
            Material material = materialRepo.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("No material with id " + id));
            if (material.getCourseId() != courseId) {
                throw new IllegalArgumentException("Material " + id + " does not belong to this course");
            }
            material.getChunks().forEach(c -> chunks.put(c.getId(), c));
        }
        if (!conceptIds.isEmpty()) {
            for (Concept concept : concepts) {
                retriever.retrieve(courseId, concept.getName() + " " + concept.getDefinition(), topK)
                        .forEach(c -> chunks.put(c.getId(), c));
            }
        }
        return new ArrayList<>(chunks.values());
    }

    /** The page the model named, if the excerpt covers it; otherwise the excerpt's first page. */
    static int pageWithin(Chunk chunk, Object page) {
        if (page instanceof Number number && number.intValue() >= chunk.getPageNumber()
                && number.intValue() <= chunk.getEndPage()) {
            return number.intValue();
        }
        return chunk.getPageNumber();
    }

    /** Lower case without punctuation, so small differences in spacing or capitals count as repeats. */
    static String normalizeText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
