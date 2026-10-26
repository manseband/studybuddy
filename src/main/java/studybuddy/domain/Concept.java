package studybuddy.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A key concept of a course (F03). Concepts are the topic taxonomy: questions and flashcards are
 * tagged with them and mastery (F09) is computed per concept.
 */
public class Concept {

    public static final int MIN_IMPORTANCE = 1;
    public static final int MAX_IMPORTANCE = 5;

    private long id;
    private long courseId;
    private String name;
    private String definition;
    private int importance;
    private final List<Integer> sourcePages = new ArrayList<>();
    private final List<Concept> prerequisites = new ArrayList<>();

    public Concept(long id, long courseId, String name, String definition, int importance) {
        this.id = id;
        this.courseId = courseId;
        setName(name);
        this.definition = definition == null ? "" : definition.trim();
        setImportance(importance);
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Concept name is required");
        }
        this.name = name.trim();
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition == null ? "" : definition.trim();
    }

    /** 1 (minor) to 5 (central). */
    public int getImportance() {
        return importance;
    }

    public void setImportance(int importance) {
        this.importance = Math.clamp(importance, MIN_IMPORTANCE, MAX_IMPORTANCE);
    }

    public List<Integer> getSourcePages() {
        return List.copyOf(sourcePages);
    }

    public void addSourcePages(List<Integer> pages) {
        for (Integer page : pages) {
            if (page != null && page > 0 && !sourcePages.contains(page)) {
                sourcePages.add(page);
            }
        }
        sourcePages.sort(null);
    }

    public List<Concept> getPrerequisites() {
        return List.copyOf(prerequisites);
    }

    /** Adds a prerequisite of the same course; self-links and duplicates are ignored. */
    public void addPrerequisite(Concept prerequisite) {
        if (prerequisite == this || prerequisite.courseId != courseId
                || prerequisites.stream().anyMatch(p -> p.matches(prerequisite.name))) {
            return;
        }
        prerequisites.add(prerequisite);
    }

    public void removePrerequisite(Concept prerequisite) {
        prerequisites.remove(prerequisite);
    }

    /** True if the other name refers to the same concept after normalization. */
    public boolean matches(String otherName) {
        return normalize(name).equals(normalize(otherName));
    }

    /**
     * Normalizes a concept name for deduplication (F03 step 3): lower case, punctuation removed,
     * whitespace collapsed and a simple plural "s" dropped from each word ("Design Patterns" == "design pattern").
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : cleaned.split("\\s+")) {
            if (word.length() > 3 && word.endsWith("ies")) {
                word = word.substring(0, word.length() - 3) + "y";
            } else if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
                word = word.substring(0, word.length() - 1);
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word);
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return name;
    }
}
