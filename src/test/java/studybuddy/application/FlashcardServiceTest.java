package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMException;
import studybuddy.ai.NoKeyLLMClient;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.Concept;
import studybuddy.domain.Flashcard;
import studybuddy.domain.FlashcardReview;
import studybuddy.domain.Material;
import studybuddy.domain.Rating;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;

/** F05 with a scripted model and a real in-memory database. */
class FlashcardServiceTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private FlashcardRepository cards;
    private ConceptRepository concepts;
    private MaterialRepository materials;
    private KeywordRetriever retriever;
    private EventBus bus;
    private FlashcardService service;
    private final List<StudyEvent> events = new ArrayList<>();
    private long courseId;
    private Material material;

    @BeforeEach
    void setUp() throws IOException {
        db = Database.inMemory();
        llm = new FakeLLMClient();
        bus = new EventBus();
        bus.subscribe(events::add);
        retriever = new KeywordRetriever();
        materials = new MaterialRepository(db);
        cards = new FlashcardRepository(db);
        concepts = new ConceptRepository(db);
        MaterialService materialService = new MaterialService(new CourseRepository(db), materials,
                new DocumentParser(), retriever, bus, 1_000_000, 400, 50);
        courseId = materialService.createCourse("EECS3311", "").getId();
        material = materialService.uploadMaterial(courseId, Files.writeString(dir.resolve("notes.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
        service = new FlashcardService(llm, retriever, new ResponseParser(), cards, materials, concepts, bus, 6, 6000);
        events.clear();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static String card(String front) {
        return "{\"front\": \"" + front + "\", \"back\": \"An answer\", \"excerpt\": 1, \"concept\": null}";
    }

    private static String reply(String... cards) {
        return "{\"cards\": [" + String.join(",", cards) + "]}";
    }

    private List<Flashcard> generate(int count) {
        return service.generateCards(courseId, List.of(), List.of(), count);
    }

    private String prompt() {
        return llm.lastRequest().systemText() + "\n" + llm.lastRequest().messages().get(0).text();
    }

    /** Saves a deck of the given fronts, skipping the model. */
    private List<Flashcard> savedDeck(String... fronts) {
        List<Flashcard> deck = new ArrayList<>();
        for (String front : fronts) {
            deck.add(Flashcard.create(courseId, 0, null, front, "Back", LocalDate.now()));
        }
        return service.saveDeck(deck);
    }

    // ----- generation -----

    @Test
    void generatesUnsavedCardsWithAConcept() {
        Concept facade = concepts.save(new Concept(0, courseId, "Facade", "Hides a subsystem.", 3));
        llm.enqueueText(reply("{\"front\": \"What is a Facade?\", \"back\": \"One simple interface.\", \"excerpt\": 1, "
                + "\"concept\": \"facade\"}"));

        List<Flashcard> generated = generate(5);

        assertEquals(1, generated.size());
        Flashcard first = generated.get(0);
        assertEquals("What is a Facade?", first.getFront());
        assertEquals(facade.getId(), first.getConceptId());
        assertEquals(1, first.getBox());
        assertEquals(LocalDate.now(), first.getNextDue());
        assertEquals(0, first.getId());
        assertTrue(cards.findByCourse(courseId).isEmpty());
        assertTrue(events.isEmpty());
        assertTrue(prompt().contains("exactly 5 flashcards"), prompt());
        assertTrue(prompt().contains("exactly as one of these names: Facade"), prompt());
    }

    @Test
    void removesEmptyTooLongDuplicateAndUnknownSourceCards() {
        llm.enqueueText(reply(card("Keep me"), card("KEEP me!"), card(" "), card("x".repeat(Flashcard.MAX_FRONT_LENGTH + 1)),
                "{\"front\": \"No back\", \"excerpt\": 1}",
                "{\"front\": \"Long back\", \"back\": \"" + "y".repeat(Flashcard.MAX_BACK_LENGTH + 1) + "\", \"excerpt\": 1}",
                card("Bad excerpt").replace("\"excerpt\": 1", "\"excerpt\": 9"),
                "{\"front\": \"No excerpt\", \"back\": \"An answer\"}"));

        assertEquals(List.of("Keep me"), generate(5).stream().map(Flashcard::getFront).toList());
    }

    @Test
    void anUnknownConceptLeavesTheCardUntagged() {
        llm.enqueueText(reply(card("A card").replace("\"concept\": null", "\"concept\": \"Quantum physics\"")));

        assertNull(generate(5).get(0).getConceptId());
    }

    @Test
    void doesNotRepeatExistingCardsAndKeepsToTheCount() {
        savedDeck("Old card");
        List<String> many = new ArrayList<>(List.of(card("old CARD")));
        for (int i = 0; i < 8; i++) {
            many.add(card("New " + i));
        }
        llm.enqueueText(reply(many.toArray(new String[0])));

        List<Flashcard> generated = generate(5);

        assertEquals(5, generated.size());
        assertFalse(generated.stream().anyMatch(c -> c.getFront().equalsIgnoreCase("old card")));
        assertTrue(prompt().contains("Do not repeat or reword these existing cards: Old card"), prompt());
    }

    @Test
    void shortMaterialAsksForFewerCards() throws IOException {
        MaterialService small = new MaterialService(new CourseRepository(db), materials, new DocumentParser(), retriever,
                bus, 1_000_000, 400, 50);
        long course = small.createCourse("Small", "").getId();
        small.uploadMaterial(course, Files.writeString(dir.resolve("small.txt"), "word ".repeat(30)));
        llm.enqueueText(reply(card("One")));

        service.generateCards(course, List.of(), List.of(), 10);

        assertTrue(prompt().contains("exactly 2 flashcards"), prompt());
    }

    @Test
    void scopesToMaterialsAndConcepts() {
        Concept facade = concepts.save(new Concept(0, courseId, "Facade", "A simple interface.", 3));
        llm.enqueueText(reply(card("One")));
        service.generateCards(courseId, List.of(material.getId()), List.of(), 5);
        assertEquals(1, llm.getRequests().size());

        retriever.index(material.getChunks());
        llm.enqueueText(reply(card("Two").replace("\"concept\": null", "\"concept\": \"Facade\"")));
        assertEquals(facade.getId(), service.generateCards(courseId, List.of(), List.of(facade.getId()), 5).get(0)
                .getConceptId());
        assertEquals(2, llm.getRequests().size());

        assertThrows(IllegalArgumentException.class, () -> service.generateCards(courseId, List.of(), List.of(999L), 5));
    }

    @Test
    void conceptScopeKeepsOnlyCardsOnThoseConcepts() {
        Concept facade = concepts.save(new Concept(0, courseId, "Facade", "A simple interface.", 3));
        concepts.save(new Concept(0, courseId, "Observer", "Notifies listeners.", 3));
        retriever.index(material.getChunks());
        llm.enqueueText(reply(card("On topic").replace("\"concept\": null", "\"concept\": \"Facade\""),
                card("Untagged"), card("Other concept").replace("\"concept\": null", "\"concept\": \"Observer\"")));

        List<Flashcard> generated = service.generateCards(courseId, List.of(), List.of(facade.getId()), 5);

        assertEquals(List.of("On topic"), generated.stream().map(Flashcard::getFront).toList());
        assertTrue(prompt().contains("Every card must test one of these concepts"), prompt());
    }

    @Test
    void rejectsCountsOutsideTheLimits() {
        assertThrows(IllegalArgumentException.class, () -> generate(Flashcard.MIN_DECK_SIZE - 1));
        assertThrows(IllegalArgumentException.class, () -> generate(Flashcard.MAX_DECK_SIZE + 1));
        assertEquals(0, llm.getRequests().size());
    }

    @Test
    void courseWithoutTextIsRejected() {
        assertThrows(IllegalStateException.class, () -> service.generateCards(courseId + 1, List.of(), List.of(), 5));
    }

    @Test
    void modelFailureOrNoValidCardIsAnError() {
        llm.enqueueFailure(new LLMException("rate limited", true));
        assertThrows(ContentGenerationException.class, () -> generate(5));

        llm.enqueueText(reply());
        assertThrows(ContentGenerationException.class, () -> generate(5));
    }

    // ----- saving -----

    @Test
    void saveDeckGivesCardsOneNewDeckAndNotifies() {
        List<Flashcard> first = savedDeck("A", "B");
        List<Flashcard> second = savedDeck("C");

        assertEquals(first.get(0).getDeckId(), first.get(1).getDeckId());
        assertTrue(second.get(0).getDeckId() > first.get(0).getDeckId());
        assertTrue(first.get(0).getId() > 0);
        assertEquals(StudyEvent.Type.FLASHCARDS_CHANGED, events.get(0).type());
        assertEquals(List.of("C", "A", "B"), service.listCards(courseId).stream().map(Flashcard::getFront).toList());
        assertThrows(IllegalArgumentException.class, () -> service.saveDeck(List.of()));
    }

    // ----- deleting -----

    @Test
    void deleteDeckRemovesItsCardsAndNotifies() {
        long kept = savedDeck("Keep").get(0).getDeckId();
        long deleted = savedDeck("Delete").get(0).getDeckId();
        events.clear();

        service.deleteDeck(deleted);

        assertEquals(List.of(kept), service.listCards(courseId).stream().map(Flashcard::getDeckId).toList());
        assertEquals(StudyEvent.Type.FLASHCARDS_CHANGED, events.get(0).type());
        assertThrows(NoSuchElementException.class, () -> service.deleteDeck(deleted));
    }

    // ----- review -----

    @Test
    void reviewMovesTheCardAndRecordsEvidence() {
        Concept facade = concepts.save(new Concept(0, courseId, "Facade", "", 3));
        Flashcard card = service.saveDeck(List.of(Flashcard.create(courseId, 0, facade.getId(), "Front", "Back",
                LocalDate.now()))).get(0);
        assertEquals(1, service.getDueCards(courseId).size());

        Flashcard reviewed = service.recordReview(card.getId(), Rating.GOOD);

        assertEquals(2, reviewed.getBox());
        assertEquals(LocalDate.now().plusDays(2), reviewed.getNextDue());
        Flashcard stored = cards.findById(card.getId()).orElseThrow();
        assertEquals(2, stored.getBox());
        assertEquals(1, stored.getReviewCount());
        assertTrue(service.getDueCards(courseId).isEmpty());
        FlashcardReview evidence = cards.findReviews(courseId).get(0);
        assertEquals(facade.getId(), evidence.conceptId());
        assertEquals(Rating.GOOD, evidence.rating());
        assertEquals(StudyEvent.Type.FLASHCARD_REVIEWED, events.getLast().type());
    }

    @Test
    void dueCardsAreMostOverdueFirstAndFutureCardsAreLeftOut() {
        LocalDate today = LocalDate.now();
        Flashcard tomorrow = Flashcard.create(courseId, 0, null, "Tomorrow", "B", today.plusDays(1));
        Flashcard dueToday = Flashcard.create(courseId, 0, null, "Today", "B", today);
        Flashcard overdue = Flashcard.create(courseId, 0, null, "Overdue", "B", today.minusDays(3));
        long deckId = service.saveDeck(List.of(tomorrow, dueToday, overdue)).get(0).getDeckId();

        assertEquals(List.of("Overdue", "Today"), service.getDueCards(courseId).stream().map(Flashcard::getFront).toList());
        assertEquals(List.of("Overdue", "Today"),
                service.getDueCardsInDeck(deckId).stream().map(Flashcard::getFront).toList());
        assertEquals(today.minusDays(3), service.nextDueDate(service.getDeck(deckId)).orElseThrow());
        assertTrue(service.nextDueDate(List.of()).isEmpty());
    }

    @Test
    void reviewWorksWithoutAnApiKey() {
        FlashcardService noKey = new FlashcardService(new NoKeyLLMClient(), retriever, new ResponseParser(), cards,
                materials, concepts, bus, 6, 6000);
        Flashcard card = savedDeck("Front").get(0);

        assertEquals(1, noKey.getDueCards(courseId).size());
        assertEquals(2, noKey.recordReview(card.getId(), Rating.GOOD).getBox());
    }

    @Test
    void missingCardAndDeckGiveClearErrors() {
        assertThrows(NoSuchElementException.class, () -> service.recordReview(99, Rating.GOOD));
        assertThrows(NoSuchElementException.class, () -> service.getDeck(99));
        assertThrows(NoSuchElementException.class, () -> service.getDueCardsInDeck(99));
    }
}
