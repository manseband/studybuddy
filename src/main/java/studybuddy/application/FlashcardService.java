package studybuddy.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import studybuddy.ai.LLMClient;
import studybuddy.ai.LLMException;
import studybuddy.ai.PromptBuilder;
import studybuddy.ai.ResponseFormatException;
import studybuddy.ai.ResponseParser;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.domain.Chunk;
import studybuddy.domain.Concept;
import studybuddy.domain.Flashcard;
import studybuddy.domain.Rating;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;

/**
 * Generates flashcards and runs spaced-repetition review (F04). The LLM only writes the cards; validation,
 * the Leitner schedule (in {@link Flashcard}) and which cards are due are deterministic, so review works
 * without an API key.
 */
public class FlashcardService {

    /** Below this many words per card there is not enough material for the requested count. */
    static final int WORDS_PER_CARD = 15;
    private static final int EXISTING_FRONTS_IN_PROMPT = 30;

    private static final String CARDS_SCHEMA = """
            {"cards": [{"front": "a question or term", "back": "a short answer",
                        "excerpt": 1, "concept": "Observer pattern"}]}""";

    private final LLMClient llm;
    private final ResponseParser responseParser;
    private final FlashcardRepository cardRepo;
    private final StudyScope scope;
    private final EventBus eventBus;

    /** Shape of the model's JSON reply. */
    record CardsJson(List<CardJson> cards) {
    }

    record CardJson(String front, String back, Integer excerpt, String concept) {
    }

    /**
     * @param topK       number of excerpts retrieved per concept when the scope is a set of concepts
     * @param wordBudget most words of material text sent in one prompt
     */
    public FlashcardService(LLMClient llm, RetrievalStrategy retriever, ResponseParser responseParser,
                            FlashcardRepository cardRepo, MaterialRepository materialRepo,
                            ConceptRepository conceptRepo, EventBus eventBus, int topK, int wordBudget) {
        this.llm = llm;
        this.responseParser = responseParser;
        this.cardRepo = cardRepo;
        this.scope = new StudyScope(materialRepo, conceptRepo, retriever, topK, wordBudget);
        this.eventBus = eventBus;
    }

    /**
     * Writes cards from the chosen materials and concepts, or the whole course if neither is chosen. When
     * concepts are chosen, every card must be about one of them. Nothing is saved: call {@link #saveDeck} after the student has reviewed them. There can be fewer
     * cards than requested if the material is short or some were invalid or repeats; compare the size.
     *
     * @throws IllegalArgumentException   if the count is outside 5-20 or the scope is not in this course
     * @throws IllegalStateException      if the scope contains no text
     * @throws ContentGenerationException if the model fails or no valid card was produced
     */
    public List<Flashcard> generateCards(long courseId, List<Long> materialIds, List<Long> conceptIds, int count) {
        if (count < Flashcard.MIN_DECK_SIZE || count > Flashcard.MAX_DECK_SIZE) {
            throw new IllegalArgumentException("The number of cards must be between " + Flashcard.MIN_DECK_SIZE
                    + " and " + Flashcard.MAX_DECK_SIZE);
        }
        List<Concept> concepts = scope.concepts(courseId, conceptIds);
        List<Chunk> excerpts = scope.excerpts(courseId, materialIds, conceptIds, concepts);
        int words = excerpts.stream().mapToInt(Chunk::wordCount).sum();
        int target = Math.min(count, Math.max(1, words / WORDS_PER_CARD));

        List<String> existing = cardRepo.findByCourse(courseId).stream().map(Flashcard::getFront).toList();
        Set<String> usedFronts = existing.stream().map(StudyScope::normalizeText)
                .collect(Collectors.toCollection(HashSet::new));
        boolean mustBeTagged = !conceptIds.isEmpty();
        CardsJson reply;
        try {
            reply = requestCards(target, excerpts, concepts, existing, mustBeTagged);
        } catch (LLMException | ResponseFormatException e) {
            throw new ContentGenerationException("The flashcards could not be generated: " + e.getMessage(),
                    List.of(), e);
        }
        List<Flashcard> cards = new ArrayList<>();
        for (CardJson json : reply.cards() == null ? List.<CardJson>of() : reply.cards()) {
            Flashcard card = toCard(json, courseId, excerpts, concepts);
            boolean onTopic = card != null && (!mustBeTagged || card.getConceptId() != null);
            if (onTopic && cards.size() < target && usedFronts.add(StudyScope.normalizeText(card.getFront()))) {
                cards.add(card);
            }
        }
        if (cards.isEmpty()) {
            throw new ContentGenerationException("The flashcards could not be generated: the model did not produce "
                    + "any valid new cards.", List.of(), null);
        }
        return cards;
    }

    /** The card described by the reply, or null if it has an empty or too long side or an unknown excerpt. */
    private static Flashcard toCard(CardJson json, long courseId, List<Chunk> excerpts, List<Concept> concepts) {
        if (json == null || json.front() == null || json.back() == null || json.front().isBlank()
                || json.back().isBlank() || json.front().length() > Flashcard.MAX_FRONT_LENGTH
                || json.back().length() > Flashcard.MAX_BACK_LENGTH || json.excerpt() == null
                || json.excerpt() < 1 || json.excerpt() > excerpts.size()) {
            return null;
        }
        Long conceptId = json.concept() == null ? null : concepts.stream().filter(c -> c.matches(json.concept()))
                .map(Concept::getId).findFirst().orElse(null);
        return Flashcard.create(courseId, 0, conceptId, json.front(), json.back(), LocalDate.now());
    }

    private CardsJson requestCards(int count, List<Chunk> excerpts, List<Concept> concepts, List<String> existing,
                                   boolean mustBeTagged) {
        PromptBuilder prompt = new PromptBuilder()
                .system("You are StudyBuddy, a study assistant. You write flashcards that help a student remember "
                        + "their course materials.")
                .withInstruction("Base every card only on the numbered excerpts. \"excerpt\" is the number of the "
                        + "excerpt the card comes from.")
                .withInstruction("Each card tests one idea. The front is a clear question or term, and the back is "
                        + "a short answer the student can check themselves against.")
                .withOutputSchema(CARDS_SCHEMA)
                .withExcerpts(excerpts);
        if (concepts.isEmpty()) {
            prompt.withInstruction("Set \"concept\" to null.");
        } else {
            prompt.withInstruction("\"concept\" is the concept the card tests, written exactly as one of these "
                    + "names: " + concepts.stream().map(Concept::getName).collect(Collectors.joining("; ")) + ". "
                    + (mustBeTagged ? "Every card must test one of these concepts; ignore other topics in the excerpts."
                            : "Use null only if none fits."));
        }
        if (!existing.isEmpty()) {
            prompt.withInstruction("Do not repeat or reword these existing cards: " + existing.stream()
                    .limit(EXISTING_FRONTS_IN_PROMPT).collect(Collectors.joining(" | ")));
        }
        return responseParser.requestJson(llm, prompt.withUserMessage("Write exactly " + count + " flashcards.").build(),
                CardsJson.class);
    }

    /** Saves the cards the student kept as one new deck, due today. */
    public List<Flashcard> saveDeck(List<Flashcard> cards) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("A deck needs at least one card");
        }
        List<Flashcard> saved = cardRepo.saveDeck(cards);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.FLASHCARDS_CHANGED, cards.get(0).getCourseId(),
                "Created a flashcard deck with " + cards.size() + " card(s)"));
        return saved;
    }

    /** All cards of the course, newest deck first. */
    public List<Flashcard> listCards(long courseId) {
        return cardRepo.findByCourse(courseId);
    }

    /** @throws NoSuchElementException if there is no such deck */
    public List<Flashcard> getDeck(long deckId) {
        List<Flashcard> cards = cardRepo.findByDeck(deckId);
        if (cards.isEmpty()) {
            throw new NoSuchElementException("No flashcard deck with id " + deckId);
        }
        return cards;
    }

    /**
     * Deletes a deck's cards. Their ratings stay, so mastery, the study history of reviews and the streak do not
     * change: they record what the student already studied.
     *
     * @throws NoSuchElementException if there is no such deck
     */
    public void deleteDeck(long deckId) {
        List<Flashcard> deck = getDeck(deckId);
        cardRepo.deleteDeck(deckId);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.FLASHCARDS_CHANGED, deck.get(0).getCourseId(),
                "Deleted Deck #" + deckId));
    }

    /** The course's cards due today or earlier, most overdue first. */
    public List<Flashcard> getDueCards(long courseId) {
        return due(listCards(courseId));
    }

    public List<Flashcard> getDueCardsInDeck(long deckId) {
        return due(getDeck(deckId));
    }

    /** When the next of these cards is due, or empty if there are no cards. */
    public Optional<LocalDate> nextDueDate(List<Flashcard> cards) {
        return cards.stream().map(Flashcard::getNextDue).min(Comparator.naturalOrder());
    }

    /**
     * Moves the card between Leitner boxes and records the rating as mastery evidence for its concept.
     *
     * @return the card with its new schedule
     */
    public Flashcard recordReview(long cardId, Rating rating) {
        Flashcard card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("No flashcard with id " + cardId));
        card.review(rating);
        cardRepo.recordReview(card, rating, LocalDateTime.now());
        eventBus.publish(StudyEvent.of(StudyEvent.Type.FLASHCARD_REVIEWED, card.getCourseId(),
                "Reviewed a flashcard: " + rating.name().toLowerCase()));
        return card;
    }

    private static List<Flashcard> due(List<Flashcard> cards) {
        LocalDate today = LocalDate.now();
        return cards.stream().filter(card -> card.isDue(today)).sorted(Comparator.comparing(Flashcard::getNextDue))
                .toList();
    }
}
