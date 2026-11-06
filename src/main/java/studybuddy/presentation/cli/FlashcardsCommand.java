package studybuddy.presentation.cli;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Flashcard;
import studybuddy.domain.Rating;

/** {@code studyagent flashcards generate|list|show|review} (F05). */
@Command(name = "flashcards", description = "Generate flashcards and review them with spaced repetition.",
        subcommands = {FlashcardsCommand.Generate.class, FlashcardsCommand.ListDecks.class, FlashcardsCommand.Show.class,
                FlashcardsCommand.Review.class})
public class FlashcardsCommand {

    @ParentCommand
    StudyAgentCli root;

    static Map<String, Object> cardJson(Flashcard card) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", card.getId());
        json.put("deckId", card.getDeckId());
        json.put("front", card.getFront());
        json.put("back", card.getBack());
        json.put("conceptId", card.getConceptId());
        json.put("sourcePage", card.getSourcePage());
        json.put("box", card.getBox());
        json.put("nextDue", card.getNextDue().toString());
        json.put("reviewCount", card.getReviewCount());
        return json;
    }

    private static String cardsText(List<Flashcard> cards) {
        return cards.stream().map(card -> "Q: " + card.getFront() + "\nA: " + card.getBack() + " (p. "
                + card.getSourcePage() + ", box " + card.getBox() + ", due " + card.getNextDue() + ")")
                .collect(Collectors.joining("\n\n"));
    }

    @Command(name = "generate", description = "Write a new deck from course materials or concepts and save it.")
    static class Generate implements Callable<Integer> {
        @ParentCommand
        FlashcardsCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Option(names = "--count", defaultValue = "10", description = "Number of cards, 5-20 (default ${DEFAULT-VALUE}).")
        int count;

        @Option(names = "--material", split = ",", description = "Only these material ids.")
        List<Long> materialIds = List.of();

        @Option(names = "--concepts", split = ",", description = "Only these concepts (names or ids).")
        List<String> concepts = List.of();

        @Option(names = "--preview", description = "Only show the cards; do not save them.")
        boolean preview;

        @Override
        public Integer call() {
            StudyAgentFacade facade = parent.root.facade();
            Course c = facade.findCourse(course);
            List<Flashcard> cards = facade.generateFlashcards(c.getId(), materialIds, conceptIds(facade, c), count);
            if (!preview) {
                cards = facade.saveFlashcards(cards);
            }
            StringBuilder text = new StringBuilder(cardsText(cards)).append("\n\n");
            text.append(preview ? cards.size() + " card(s) generated (preview, not saved)."
                    : "Created deck #" + cards.get(0).getDeckId() + " with " + cards.size()
                            + " card(s). Review it with: studyagent flashcards review --deck "
                            + cards.get(0).getDeckId());
            if (cards.size() < count) {
                text.append("\nNote: only ").append(cards.size()).append(" of the ").append(count)
                        .append(" requested cards were generated. The material may be too short, or some cards "
                                + "repeated existing ones.");
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("deckId", preview ? null : cards.get(0).getDeckId());
            json.put("requested", count);
            json.put("saved", !preview);
            json.put("cards", cards.stream().map(FlashcardsCommand::cardJson).toList());
            parent.root.printResult(json, text.toString());
            return 0;
        }

        private List<Long> conceptIds(StudyAgentFacade facade, Course c) {
            List<Long> ids = new ArrayList<>();
            List<Concept> all = facade.listConcepts(c.getId());
            for (String value : concepts) {
                Concept concept = all.stream().filter(k -> k.matches(value) || String.valueOf(k.getId())
                        .equals(value.strip())).findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "This course has no concept \"" + value.strip() + "\""));
                ids.add(concept.getId());
            }
            return ids;
        }
    }

    @Command(name = "list", description = "List a course's decks and how many cards are due today.")
    static class ListDecks implements Callable<Integer> {
        @ParentCommand
        FlashcardsCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Override
        public Integer call() {
            Course c = parent.root.facade().findCourse(course);
            List<Flashcard> cards = parent.root.facade().listFlashcards(c.getId());
            Map<Long, List<Flashcard>> decks = cards.stream().collect(Collectors.groupingBy(Flashcard::getDeckId,
                    () -> new TreeMap<Long, List<Flashcard>>(), Collectors.toList()));
            LocalDate today = LocalDate.now();
            List<Map<String, Object>> rows = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            decks.forEach((deckId, deck) -> {
                long due = deck.stream().filter(card -> card.isDue(today)).count();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("deckId", deckId);
                row.put("cards", deck.size());
                row.put("due", due);
                rows.add(row);
                text.append(String.format("Deck #%-4d %3d card(s), %d due today%n", deckId, deck.size(), due));
            });
            long totalDue = cards.stream().filter(card -> card.isDue(today)).count();
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("decks", rows);
            json.put("dueToday", totalDue);
            parent.root.printResult(json, cards.isEmpty()
                    ? "No flashcards yet. Create some with: studyagent flashcards generate --course " + course
                    : text + "\n" + totalDue + " card(s) due today in this course.");
            return 0;
        }
    }

    @Command(name = "show", description = "Show the cards of a deck.")
    static class Show implements Callable<Integer> {
        @ParentCommand
        FlashcardsCommand parent;

        @Parameters(index = "0", description = "Deck id.")
        long deckId;

        @Override
        public Integer call() {
            List<Flashcard> cards = parent.root.facade().getDeck(deckId);
            parent.root.printResult(cards.stream().map(FlashcardsCommand::cardJson).toList(), cardsText(cards));
            return 0;
        }
    }

    @Command(name = "review", description = "Review the cards that are due, one at a time.")
    static class Review implements Callable<Integer> {
        @ParentCommand
        FlashcardsCommand parent;

        @Option(names = "--deck", description = "Review only this deck.")
        Long deckId;

        @Option(names = "--course", description = "Review all due cards of this course (name or id).")
        String course;

        @Override
        public Integer call() throws IOException {
            if ((deckId == null) == (course == null)) {
                throw new IllegalArgumentException("Choose what to review with either --deck or --course.");
            }
            StudyAgentFacade facade = parent.root.facade();
            List<Flashcard> due = deckId != null ? facade.getDueCardsInDeck(deckId)
                    : facade.getDueCards(facade.findCourse(course).getId());
            Map<String, Integer> ratings = new LinkedHashMap<>();
            List<Map<String, Object>> reviewed = new ArrayList<>();
            for (int i = 0; i < due.size(); i++) {
                Flashcard card = due.get(i);
                parent.root.prompt("Card " + (i + 1) + " of " + due.size() + "\nQ: " + card.getFront()
                        + "\nPress Enter to show the answer (q to stop): ");
                String reveal = parent.root.readLine();
                if (reveal == null || reveal.strip().equalsIgnoreCase("q")) {
                    break;
                }
                parent.root.prompt("A: " + card.getBack() + " (p. " + card.getSourcePage() + ")\n");
                Rating rating = askRating();
                if (rating == null) {
                    break;
                }
                reviewed.add(cardJson(facade.reviewFlashcard(card.getId(), rating)));
                ratings.merge(rating.name().toLowerCase(), 1, Integer::sum);
                parent.root.prompt("\n");
            }
            printSummary(facade, reviewed, ratings);
            return 0;
        }

        /** Asks until the answer is a rating; null if the student stops. */
        private Rating askRating() throws IOException {
            while (true) {
                parent.root.prompt("Rate your recall: (a)gain, (h)ard, (g)ood, (e)asy, q to stop: ");
                String line = parent.root.readLine();
                if (line == null || line.strip().equalsIgnoreCase("q")) {
                    return null;
                }
                switch (line.strip().toLowerCase()) {
                    case "a", "again":
                        return Rating.AGAIN;
                    case "h", "hard":
                        return Rating.HARD;
                    case "g", "good":
                        return Rating.GOOD;
                    case "e", "easy":
                        return Rating.EASY;
                    default:
                        break;
                }
            }
        }

        private void printSummary(StudyAgentFacade facade, List<Map<String, Object>> reviewed,
                                  Map<String, Integer> ratings) {
            List<Flashcard> scope = deckId != null ? facade.getDeck(deckId)
                    : facade.listFlashcards(facade.findCourse(course).getId());
            Optional<LocalDate> next = facade.getNextDueDate(scope);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("reviewed", reviewed.size());
            json.put("ratings", ratings);
            json.put("cards", reviewed);
            json.put("nextDue", next.map(LocalDate::toString).orElse(null));
            String nextText = next.map(date -> " The next card is due on " + date + ".").orElse("");
            parent.root.printResult(json, reviewed.isEmpty() ? "No cards reviewed." + nextText
                    : "Reviewed " + reviewed.size() + " card(s) " + ratings + "." + nextText);
        }
    }
}
