package studybuddy.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import studybuddy.ai.LLMClient;
import studybuddy.ai.LLMException;
import studybuddy.ai.PromptBuilder;
import studybuddy.ai.ResponseFormatException;
import studybuddy.ai.ResponseParser;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.domain.Answer;
import studybuddy.domain.Chunk;
import studybuddy.domain.Citation;
import studybuddy.domain.Concept;
import studybuddy.domain.Material;
import studybuddy.domain.QaEntry;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;

/**
 * AI-generated learning content from the course materials: answers to questions (F04) and the
 * course's key concepts (F03). Explanations (F08) are added here when implemented.
 *
 * Retrieval, chunk selection, deduplication and validation are deterministic; the LLM only writes the text.
 */
public class LearningContentService {

    public static final int MAX_QUESTION_LENGTH = 1000;
    static final String NOT_COVERED_TEXT = "Your course materials do not seem to cover this question.";
    /** F03: "Too many concepts (more than 40 per material): only the most important are kept." */
    public static final int MAX_CONCEPTS_PER_MATERIAL = 40;

    private static final String CONCEPTS_SCHEMA = """
            {"concepts": [{"name": "Observer pattern",
                           "definition": "one sentence",
                           "pages": [3],
                           "importance": 4,
                           "prerequisites": ["Interface"]}]}""";

    private static final String ANSWER_SCHEMA = """
            {"answer": "your answer, citing excerpts like [1] or [2][3]",
             "citations": [1, 2],
             "covered": true}""";

    private final LLMClient llm;
    private final RetrievalStrategy retriever;
    private final ResponseParser responseParser;
    private final MaterialRepository materialRepo;
    private final QaHistoryRepository qaRepo;
    private final ConceptRepository conceptRepo;
    private final EventBus eventBus;
    private final int topK;
    private final int wordBudget;

    /** Shape of the model's JSON reply for a question. */
    record AnswerJson(String answer, List<Integer> citations, Boolean covered) {
    }

    record GeneralAnswerJson(String answer) {
    }

    /** Shape of the model's JSON reply for concept extraction. */
    record ConceptsJson(List<ConceptJson> concepts) {
    }

    record ConceptJson(String name, String definition, List<Integer> pages, Integer importance,
                       List<String> prerequisites) {
    }

    /** A concept candidate gathered from one or more chunk groups, before matching existing concepts. */
    private static final class Candidate {
        static final int DEFAULT_IMPORTANCE = 3;

        final String name;
        String definition = "";
        /** Highest importance any group gave this concept; null until one gives a value. */
        Integer importance;
        final Set<Integer> pages = new TreeSet<>();
        final Set<String> prerequisites = new LinkedHashSet<>();

        Candidate(String name) {
            this.name = name;
        }

        int importance() {
            return importance == null ? DEFAULT_IMPORTANCE : importance;
        }

        void add(ConceptJson json, int pageCount) {
            if (definition.isBlank() && json.definition() != null) {
                definition = json.definition().strip();
            }
            if (json.importance() != null) {
                int value = Math.clamp(json.importance(), Concept.MIN_IMPORTANCE, Concept.MAX_IMPORTANCE);
                importance = importance == null ? value : Math.max(importance, value);
            }
            if (json.pages() != null) {
                json.pages().stream().filter(p -> p != null && p >= 1 && p <= pageCount).forEach(pages::add);
            }
            if (json.prerequisites() != null) {
                json.prerequisites().stream().filter(p -> p != null && !p.isBlank()).map(String::strip)
                        .forEach(prerequisites::add);
            }
        }
    }

    /**
     * @param topK       number of excerpts retrieved for a question (F04)
     * @param wordBudget most words of material text sent in one prompt; longer materials are processed
     *                   in several groups of chunks (F03)
     */
    public LearningContentService(LLMClient llm, RetrievalStrategy retriever, ResponseParser responseParser,
                                  MaterialRepository materialRepo, QaHistoryRepository qaRepo,
                                  ConceptRepository conceptRepo, EventBus eventBus, int topK, int wordBudget) {
        this.llm = llm;
        this.retriever = retriever;
        this.responseParser = responseParser;
        this.materialRepo = materialRepo;
        this.qaRepo = qaRepo;
        this.conceptRepo = conceptRepo;
        this.eventBus = eventBus;
        this.topK = topK;
        this.wordBudget = wordBudget;
    }

    public Answer answerQuestion(long courseId, String question) {
        return answerQuestion(courseId, question, false);
    }

    /**
     * Answers a question from the course's materials, citing the excerpts used (F04).
     *
     * @param allowGeneralKnowledge if the materials do not cover the question, answer from general
     *                              knowledge (labelled as such) instead of only saying so
     * @throws IllegalArgumentException    for an empty or too long question
     * @throws IllegalStateException       if the course has no searchable materials
     * @throws ContentGenerationException  if the model fails; carries the retrieved excerpts
     */
    public Answer answerQuestion(long courseId, String question, boolean allowGeneralKnowledge) {
        String q = validateQuestion(question);
        if (materialRepo.findChunksByCourse(courseId).isEmpty()) {
            throw new IllegalStateException("This course has no searchable materials yet. Upload materials first.");
        }

        List<Chunk> excerpts = retriever.retrieve(courseId, q, topK);
        Map<Long, String> titles = materialTitles(excerpts);
        Answer answer;
        try {
            answer = excerpts.isEmpty()
                    ? notCoveredAnswer(q, allowGeneralKnowledge)
                    : groundedAnswer(q, excerpts, titles, allowGeneralKnowledge);
        } catch (LLMException | ResponseFormatException e) {
            throw new ContentGenerationException("The answer could not be generated: " + e.getMessage(),
                    excerptCitations(excerpts, titles), e);
        }

        qaRepo.save(new QaEntry(0, courseId, q, answer, LocalDateTime.now()));
        eventBus.publish(StudyEvent.of(StudyEvent.Type.QUESTION_ANSWERED, courseId, "Asked: " + q));
        return answer;
    }

    public List<QaEntry> getQuestionHistory(long courseId) {
        return qaRepo.findByCourse(courseId);
    }

    // ----- F03 concepts -----

    /**
     * Identifies the key concepts of a material (F03). The chunks are sent in groups; candidates are
     * deduplicated by normalized name and matched with the course's existing concepts, and only
     * prerequisite links to known concepts are kept. At most {@link #MAX_CONCEPTS_PER_MATERIAL}
     * concepts are returned, most important first. Nothing is saved: call {@link #saveConcepts}
     * after the student has reviewed the list.
     *
     * @return candidates; new concepts have id 0, matches of existing concepts keep their id
     */
    public List<Concept> extractConcepts(long materialId) {
        Material material = materialRepo.findById(materialId)
                .orElseThrow(() -> new NoSuchElementException("No material with id " + materialId));
        if (!material.hasText()) {
            throw new IllegalStateException("\"" + material.getTitle() + "\" has no extractable text.");
        }

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        try {
            for (List<Chunk> group : groupByWordBudget(material.getChunks())) {
                ConceptsJson reply = requestConcepts(group);
                for (ConceptJson json : reply.concepts() == null ? List.<ConceptJson>of() : reply.concepts()) {
                    String key = json == null ? "" : Concept.normalize(json.name());
                    if (!key.isEmpty()) {
                        candidates.computeIfAbsent(key, k -> new Candidate(json.name().strip()))
                                .add(json, material.getPageCount());
                    }
                }
            }
        } catch (LLMException | ResponseFormatException e) {
            throw new ContentGenerationException("Concepts could not be extracted: " + e.getMessage(), List.of(), e);
        }

        List<Candidate> kept = candidates.values().stream()
                .sorted(Comparator.comparingInt(Candidate::importance).reversed())
                .limit(MAX_CONCEPTS_PER_MATERIAL)
                .toList();
        List<Concept> existing = conceptRepo.findByCourse(material.getCourseId());
        Map<String, Concept> results = new LinkedHashMap<>();
        for (Candidate candidate : kept) {
            Concept concept = findByName(existing, candidate.name);
            if (concept == null) {
                concept = new Concept(0, material.getCourseId(), candidate.name, candidate.definition,
                        candidate.importance());
            } else {
                concept.setImportance(Math.max(concept.getImportance(), candidate.importance()));
                if (concept.getDefinition().isBlank()) {
                    concept.setDefinition(candidate.definition);
                }
            }
            concept.addSourcePages(List.copyOf(candidate.pages));
            results.put(Concept.normalize(candidate.name), concept);
        }
        for (Candidate candidate : kept) {
            Concept concept = results.get(Concept.normalize(candidate.name));
            for (String name : candidate.prerequisites) {
                Concept prerequisite = results.get(Concept.normalize(name));
                if (prerequisite == null) {
                    prerequisite = findByName(existing, name);
                }
                if (prerequisite != null) {
                    concept.addPrerequisite(prerequisite);
                }
            }
        }
        return new ArrayList<>(results.values());
    }

    /** Saves reviewed concepts of a material and records that they appear in it. */
    public List<Concept> saveConcepts(long materialId, List<Concept> concepts) {
        Material material = materialRepo.findById(materialId)
                .orElseThrow(() -> new NoSuchElementException("No material with id " + materialId));
        for (Concept concept : concepts) {
            if (concept.getCourseId() != material.getCourseId()) {
                throw new IllegalArgumentException("Concept \"" + concept.getName() + "\" belongs to another course");
            }
        }
        // first pass gives new concepts their ids, second pass stores links between new concepts
        concepts.forEach(conceptRepo::save);
        concepts.stream().filter(c -> !c.getPrerequisites().isEmpty()).forEach(conceptRepo::save);
        concepts.forEach(c -> conceptRepo.linkMaterial(c.getId(), materialId));
        eventBus.publish(StudyEvent.of(StudyEvent.Type.CONCEPTS_CHANGED, material.getCourseId(),
                "Saved " + concepts.size() + " concept(s) from " + material.getTitle()));
        return concepts;
    }

    public List<Concept> listConcepts(long courseId) {
        return conceptRepo.findByCourse(courseId);
    }

    public Concept getConcept(long conceptId) {
        return conceptRepo.findById(conceptId)
                .orElseThrow(() -> new NoSuchElementException("No concept with id " + conceptId));
    }

    /** Materials the concept was extracted from. */
    public List<Material> getConceptMaterials(long conceptId) {
        return conceptRepo.findMaterialIds(conceptId).stream()
                .map(materialRepo::findById).flatMap(Optional::stream).toList();
    }

    /** Edits a concept; null arguments leave that field unchanged. */
    public Concept updateConcept(long conceptId, String name, String definition, Integer importance) {
        Concept concept = getConcept(conceptId);
        if (name != null && !concept.matches(name)) {
            Concept clash = findByName(conceptRepo.findByCourse(concept.getCourseId()), name);
            if (clash != null) {
                throw new IllegalArgumentException("The course already has a concept \"" + clash.getName()
                        + "\" (#" + clash.getId() + "). Merge them instead.");
            }
        }
        if (name != null) {
            concept.setName(name);
        }
        if (definition != null) {
            concept.setDefinition(definition);
        }
        if (importance != null) {
            concept.setImportance(importance);
        }
        conceptRepo.save(concept);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.CONCEPTS_CHANGED, concept.getCourseId(),
                "Edited concept " + concept.getName()));
        return concept;
    }

    /** Merges one concept into another; questions, flashcards and links move to the kept concept. */
    public Concept mergeConcepts(long fromId, long intoId) {
        Concept from = getConcept(fromId);
        Concept into = getConcept(intoId);
        if (from.getCourseId() != into.getCourseId()) {
            throw new IllegalArgumentException("Only concepts of the same course can be merged");
        }
        into.addSourcePages(from.getSourcePages());
        into.setImportance(Math.max(into.getImportance(), from.getImportance()));
        if (into.getDefinition().isBlank()) {
            into.setDefinition(from.getDefinition());
        }
        conceptRepo.save(into);
        conceptRepo.merge(fromId, intoId);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.CONCEPTS_CHANGED, into.getCourseId(),
                "Merged concept " + from.getName() + " into " + into.getName()));
        return getConcept(intoId);
    }

    /** Deletes a concept; questions and flashcards tagged with it become untagged. */
    public void deleteConcept(long conceptId) {
        Concept concept = getConcept(conceptId);
        conceptRepo.delete(conceptId);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.CONCEPTS_CHANGED, concept.getCourseId(),
                "Deleted concept " + concept.getName()));
    }

    private ConceptsJson requestConcepts(List<Chunk> group) {
        return responseParser.requestJson(llm, new PromptBuilder()
                .system("You are StudyBuddy, a study assistant. You identify the key concepts a student must learn "
                        + "from their course material.")
                .withInstruction("Use only the excerpts. Each excerpt is labelled with its page or page range.")
                .withInstruction("For each concept give: a short singular name, a one-sentence definition, the pages "
                        + "it appears on, an importance from 1 (minor detail) to 5 (central idea), and the names of "
                        + "concepts a student should understand first (prerequisites).")
                .withInstruction("Return at most 20 concepts. Skip trivial terms.")
                .withOutputSchema(CONCEPTS_SCHEMA)
                .withExcerpts(group)
                .withUserMessage("List the key concepts in these excerpts.")
                .build(), ConceptsJson.class);
    }

    private static Concept findByName(List<Concept> concepts, String name) {
        return concepts.stream().filter(c -> c.matches(name)).findFirst().orElse(null);
    }

    /** Splits chunks into consecutive groups whose total word count stays within the budget. */
    private List<List<Chunk>> groupByWordBudget(List<Chunk> chunks) {
        List<List<Chunk>> groups = new ArrayList<>();
        List<Chunk> current = new ArrayList<>();
        int words = 0;
        for (Chunk chunk : chunks) {
            if (!current.isEmpty() && words + chunk.wordCount() > wordBudget) {
                groups.add(current);
                current = new ArrayList<>();
                words = 0;
            }
            current.add(chunk);
            words += chunk.wordCount();
        }
        groups.add(current);
        return groups;
    }

    private Answer groundedAnswer(String question, List<Chunk> excerpts, Map<Long, String> titles,
                                  boolean allowGeneralKnowledge) {
        AnswerJson reply = responseParser.requestJson(llm, new PromptBuilder()
                .system("You are StudyBuddy, a study assistant. You answer a student's question using only the "
                        + "numbered excerpts from their course materials.")
                .withInstruction("Use only information from the excerpts. Do not add outside knowledge.")
                .withInstruction("Cite the excerpt number after each claim, like [1] or [2][3].")
                .withInstruction("List every excerpt number you cited in \"citations\".")
                .withInstruction("If the excerpts do not contain the answer, set \"covered\" to false and leave "
                        + "\"answer\" empty.")
                .withInstruction("Keep the answer clear and concise, suitable for a student revising.")
                .withOutputSchema(ANSWER_SCHEMA)
                .withExcerpts(excerpts)
                .withUserMessage("Question: " + question)
                .build(), AnswerJson.class);

        boolean covered = reply.covered() == null || reply.covered();
        if (!covered || reply.answer() == null || reply.answer().isBlank()) {
            return notCoveredAnswer(question, allowGeneralKnowledge);
        }

        List<Citation> cited = new ArrayList<>();
        for (Integer n : reply.citations() == null ? List.<Integer>of() : reply.citations()) {
            if (n != null) {
                cited.add(new Citation(n, 0, 0, "", 0, ""));
            }
        }
        Answer checked = responseParser.validateCitations(
                new Answer(reply.answer().strip(), cited, true, false, false), excerpts);
        List<Citation> withTitles = checked.citations().stream()
                .map(c -> new Citation(c.excerptNumber(), c.chunkId(), c.materialId(),
                        titles.getOrDefault(c.materialId(), ""), c.page(), c.endPage(), c.excerpt()))
                .toList();
        // an answer without any valid citation cannot be checked against the materials
        return checked.withCitations(withTitles, withTitles.isEmpty());
    }

    private Answer notCoveredAnswer(String question, boolean allowGeneralKnowledge) {
        if (!allowGeneralKnowledge) {
            return Answer.notCovered(NOT_COVERED_TEXT);
        }
        GeneralAnswerJson reply = responseParser.requestJson(llm, new PromptBuilder()
                .system("You are StudyBuddy, a study assistant. The student's course materials do not cover this "
                        + "question, so answer from general knowledge.")
                .withInstruction("Keep the answer short and accurate. Say so if you are not sure.")
                .withOutputSchema("{\"answer\": \"your answer\"}")
                .withUserMessage("Question: " + question)
                .build(), GeneralAnswerJson.class);
        return Answer.generalKnowledge(reply.answer() == null ? "" : reply.answer().strip());
    }

    private static String validateQuestion(String question) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("Please enter a question.");
        }
        if (q.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("Questions can be at most " + MAX_QUESTION_LENGTH + " characters.");
        }
        return q;
    }

    private Map<Long, String> materialTitles(List<Chunk> chunks) {
        Map<Long, String> titles = new HashMap<>();
        for (Chunk chunk : chunks) {
            titles.computeIfAbsent(chunk.getMaterialId(),
                    id -> materialRepo.findById(id).map(Material::getTitle).orElse(""));
        }
        return titles;
    }

    private static List<Citation> excerptCitations(List<Chunk> excerpts, Map<Long, String> titles) {
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < excerpts.size(); i++) {
            Chunk c = excerpts.get(i);
            citations.add(new Citation(i + 1, c.getId(), c.getMaterialId(), titles.getOrDefault(c.getMaterialId(), ""),
                    c.getPageNumber(), c.getEndPage(), c.getText()));
        }
        return citations;
    }
}
