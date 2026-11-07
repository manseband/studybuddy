package studybuddy.presentation.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import studybuddy.application.ContentGenerationException;
import studybuddy.domain.ConceptMastery;
import studybuddy.domain.Course;

/** {@code studybuddy weak-concepts --course EECS3311 [--diagnose]} (F09). */
@Command(name = "weak-concepts", description = "Rank a course's concepts by mastery; --diagnose adds an AI analysis.")
public class WeakConceptsCommand implements Callable<Integer> {

    @ParentCommand
    StudyAgentCli root;

    @Option(names = "--course", required = true, description = "Course name or id.")
    String course;

    @Option(names = "--diagnose", description = "Ask the AI for patterns across your mistakes on weak concepts.")
    boolean diagnose;

    @Override
    public Integer call() {
        Course c = root.facade().findCourse(course);
        List<ConceptMastery> mastery = root.facade().getMastery(c.getId());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("concepts", mastery.stream().map(WeakConceptsCommand::toJson).toList());
        StringBuilder text = new StringBuilder(toText(mastery));
        if (diagnose) {
            try {
                String diagnosis = root.facade().diagnoseWeakConcepts(c.getId());
                json.put("diagnosis", diagnosis);
                text.append("\n\n").append(diagnosis);
            } catch (ContentGenerationException e) {
                json.put("diagnosisError", e.getMessage());
                text.append("\n\nDiagnosis unavailable: ").append(e.getMessage());
            }
        }
        root.printResult(json, text.toString());
        return 0;
    }

    static String toText(List<ConceptMastery> mastery) {
        if (mastery.isEmpty()) {
            return "This course has no concepts yet, and mastery is tracked per concept. Extract them with: "
                    + "studybuddy concepts extract --material ID";
        }
        if (mastery.stream().allMatch(m -> m.evidenceCount() == 0)) {
            return "No evidence yet. Take a quiz or review flashcards on this course. Quizzes and decks created before "
                    + "the concepts were extracted do not count.";
        }
        StringBuilder text = new StringBuilder();
        for (ConceptMastery m : mastery) {
            String score = m.evidenceCount() == 0 ? "-" : String.format("%.0f", m.score());
            text.append(String.format("%-16s %3s  %-30s evidence %d, trend %s%n", m.statusLabel(), score,
                    m.conceptName(), m.evidenceCount(), m.trend().name().toLowerCase()));
        }
        return text.toString().stripTrailing();
    }

    static Map<String, Object> toJson(ConceptMastery m) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conceptId", m.conceptId());
        json.put("concept", m.conceptName());
        json.put("score", m.score());
        json.put("evidenceCount", m.evidenceCount());
        json.put("trend", m.trend().name().toLowerCase());
        json.put("weak", m.weak());
        json.put("insufficientData", m.insufficientData());
        return json;
    }
}
