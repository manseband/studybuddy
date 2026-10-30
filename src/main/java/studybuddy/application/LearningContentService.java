package studybuddy.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import studybuddy.ai.LLMClient;
import studybuddy.ai.LLMException;
import studybuddy.ai.PromptBuilder;
import studybuddy.ai.ResponseFormatException;
import studybuddy.ai.ResponseParser;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.domain.Answer;
import studybuddy.domain.Chunk;
import studybuddy.domain.Citation;
import studybuddy.domain.Material;
import studybuddy.domain.QaEntry;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;

/**
 * AI-generated learning content from the course materials: answers to questions (F04).
 * Summaries (F02), concepts (F03) and explanations (F08) are added here as they are implemented.
 *
 * Retrieval and citation checking are deterministic; the LLM only writes the text.
 */
public class LearningContentService {

    public static final int MAX_QUESTION_LENGTH = 1000;
    static final String NOT_COVERED_TEXT = "Your course materials do not seem to cover this question.";

    private static final String ANSWER_SCHEMA = """
            {"answer": "your answer, citing excerpts like [1] or [2][3]",
             "citations": [1, 2],
             "covered": true}""";

    private final LLMClient llm;
    private final RetrievalStrategy retriever;
    private final ResponseParser responseParser;
    private final MaterialRepository materialRepo;
    private final QaHistoryRepository qaRepo;
    private final EventBus eventBus;
    private final int topK;

    /** Shape of the model's JSON reply for a question. */
    record AnswerJson(String answer, List<Integer> citations, Boolean covered) {
    }

    record GeneralAnswerJson(String answer) {
    }

    public LearningContentService(LLMClient llm, RetrievalStrategy retriever, ResponseParser responseParser,
                                  MaterialRepository materialRepo, QaHistoryRepository qaRepo, EventBus eventBus,
                                  int topK) {
        this.llm = llm;
        this.retriever = retriever;
        this.responseParser = responseParser;
        this.materialRepo = materialRepo;
        this.qaRepo = qaRepo;
        this.eventBus = eventBus;
        this.topK = topK;
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
                        titles.getOrDefault(c.materialId(), ""), c.page(), c.excerpt()))
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
                    c.getPageNumber(), c.getText()));
        }
        return citations;
    }
}
