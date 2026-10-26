package studybuddy.domain;

import java.time.LocalDate;

/**
 * A flashcard scheduled with Leitner boxes (F05).
 * Box 1 is reviewed every day; each higher box doubles the interval (1, 2, 4, 8, 16 days).
 * Again sends the card back to box 1, Hard keeps it in its box, Good moves it up one box and Easy two.
 * Cards generated together share a deckId. conceptId is null for an untagged card.
 */
public class Flashcard {

    public static final int MIN_BOX = 1;
    public static final int MAX_BOX = 5;

    private long id;
    private long courseId;
    private long deckId;
    private Long conceptId;
    private String front;
    private String back;
    private int sourcePage;
    private int box;
    private LocalDate nextDue;
    private int reviewCount;

    public Flashcard(long id, long courseId, long deckId, Long conceptId, String front, String back,
                     int sourcePage, int box, LocalDate nextDue, int reviewCount) {
        this.id = id;
        this.courseId = courseId;
        this.deckId = deckId;
        this.conceptId = conceptId;
        setContent(front, back);
        this.sourcePage = sourcePage;
        this.box = Math.clamp(box, MIN_BOX, MAX_BOX);
        this.nextDue = nextDue;
        this.reviewCount = reviewCount;
    }

    /** A new card in box 1, due today. */
    public static Flashcard create(long courseId, long deckId, Long conceptId, String front, String back,
                                   int sourcePage, LocalDate today) {
        return new Flashcard(0, courseId, deckId, conceptId, front, back, sourcePage, MIN_BOX, today, 0);
    }

    /** Records a review today. */
    public void review(Rating rating) {
        review(rating, LocalDate.now());
    }

    /** Moves the card between boxes and sets the next due date. */
    public void review(Rating rating, LocalDate today) {
        box = switch (rating) {
            case AGAIN -> MIN_BOX;
            case HARD -> box;
            case GOOD -> Math.min(box + 1, MAX_BOX);
            case EASY -> Math.min(box + 2, MAX_BOX);
        };
        nextDue = today.plusDays(intervalDays(box));
        reviewCount++;
    }

    /** Days until the next review for a card in the given box: 1, 2, 4, 8, 16. */
    public static int intervalDays(int box) {
        return 1 << (Math.clamp(box, MIN_BOX, MAX_BOX) - 1);
    }

    public boolean isDue(LocalDate today) {
        return !nextDue.isAfter(today);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getCourseId() {
        return courseId;
    }

    public long getDeckId() {
        return deckId;
    }

    public void setDeckId(long deckId) {
        this.deckId = deckId;
    }

    public Long getConceptId() {
        return conceptId;
    }

    public void setConceptId(Long conceptId) {
        this.conceptId = conceptId;
    }

    public String getFront() {
        return front;
    }

    public String getBack() {
        return back;
    }

    public final void setContent(String front, String back) {
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            throw new IllegalArgumentException("Both sides of a flashcard must have text");
        }
        this.front = front.trim();
        this.back = back.trim();
    }

    public int getSourcePage() {
        return sourcePage;
    }

    public int getBox() {
        return box;
    }

    public LocalDate getNextDue() {
        return nextDue;
    }

    public int getReviewCount() {
        return reviewCount;
    }
}
