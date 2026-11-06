package studybuddy.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import studybuddy.domain.Flashcard;
import studybuddy.domain.FlashcardReview;
import studybuddy.domain.Rating;

/**
 * SQLite storage for flashcards and their review history (F05). A deck is the set of cards with the
 * same deck id; deck ids are unique across courses.
 */
public class FlashcardRepository implements Repository<Flashcard> {

    private final Database db;

    public FlashcardRepository(Database db) {
        this.db = db;
    }

    @Override
    public Flashcard save(Flashcard card) {
        return db.withConnection(c -> {
            saveCard(c, card);
            return card;
        });
    }

    /** Saves the cards as one new deck, all or nothing, and gives them the new deck's id. */
    public List<Flashcard> saveDeck(List<Flashcard> cards) {
        return db.inTransaction(c -> {
            long deckId = nextDeckId(c);
            for (Flashcard card : cards) {
                card.setDeckId(deckId);
                saveCard(c, card);
            }
            return cards;
        });
    }

    /** Saves the card's new schedule together with the rating that caused it. */
    public void recordReview(Flashcard card, Rating rating, LocalDateTime reviewedAt) {
        db.inTransaction(c -> {
            saveCard(c, card);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO flashcard_reviews(card_id, course_id, concept_id, rating, reviewed_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, card.getId());
                ps.setLong(2, card.getCourseId());
                ps.setObject(3, card.getConceptId());
                ps.setString(4, rating.name());
                ps.setString(5, reviewedAt.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Flashcard> findById(long id) {
        return query("SELECT * FROM flashcards WHERE id = ?", id).stream().findFirst();
    }

    /** Newest deck first; cards in the order they were saved. */
    @Override
    public List<Flashcard> findByCourse(long courseId) {
        return query("SELECT * FROM flashcards WHERE course_id = ? ORDER BY deck_id DESC, id", courseId);
    }

    public List<Flashcard> findByDeck(long deckId) {
        return query("SELECT * FROM flashcards WHERE deck_id = ? ORDER BY id", deckId);
    }

    /** All ratings given in the course, oldest first. */
    public List<FlashcardReview> findReviews(long courseId) {
        return db.withConnection(c -> {
            List<FlashcardReview> reviews = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM flashcard_reviews WHERE course_id = ? ORDER BY reviewed_at, id")) {
                ps.setLong(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        reviews.add(new FlashcardReview(rs.getLong("card_id"), rs.getLong("course_id"),
                                SqlUtil.nullableLong(rs, "concept_id"), Rating.valueOf(rs.getString("rating")),
                                LocalDateTime.parse(rs.getString("reviewed_at"))));
                    }
                }
            }
            return reviews;
        });
    }

    @Override
    public void delete(long id) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM flashcards WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    private static long nextDeckId(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(deck_id), 0) + 1 FROM flashcards")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void saveCard(Connection c, Flashcard card) throws SQLException {
        if (card.getId() == 0) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO flashcards(course_id, deck_id, concept_id, front, back, source_page, box, next_due, "
                            + "review_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, card.getCourseId());
                ps.setLong(2, card.getDeckId());
                bindContent(ps, 3, card);
                ps.executeUpdate();
                card.setId(SqlUtil.generatedId(ps));
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE flashcards SET concept_id = ?, front = ?, back = ?, source_page = ?, box = ?, "
                            + "next_due = ?, review_count = ? WHERE id = ?")) {
                bindContent(ps, 1, card);
                ps.setLong(8, card.getId());
                ps.executeUpdate();
            }
        }
    }

    private static void bindContent(PreparedStatement ps, int first, Flashcard card) throws SQLException {
        ps.setObject(first, card.getConceptId());
        ps.setString(first + 1, card.getFront());
        ps.setString(first + 2, card.getBack());
        ps.setInt(first + 3, card.getSourcePage());
        ps.setInt(first + 4, card.getBox());
        ps.setString(first + 5, card.getNextDue().toString());
        ps.setInt(first + 6, card.getReviewCount());
    }

    private List<Flashcard> query(String sql, long param) {
        return db.withConnection(c -> {
            List<Flashcard> cards = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cards.add(new Flashcard(rs.getLong("id"), rs.getLong("course_id"), rs.getLong("deck_id"),
                                SqlUtil.nullableLong(rs, "concept_id"), rs.getString("front"), rs.getString("back"),
                                rs.getInt("source_page"), rs.getInt("box"), LocalDate.parse(rs.getString("next_due")),
                                rs.getInt("review_count")));
                    }
                }
            }
            return cards;
        });
    }
}
