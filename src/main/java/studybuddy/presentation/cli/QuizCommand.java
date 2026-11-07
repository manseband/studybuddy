package studybuddy.presentation.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import studybuddy.domain.AttemptStatus;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.GradeResult;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;

/** {@code studyagent quiz generate|list|show|take|grade|results|regrade|dispute} (F06, F07). */
@Command(name = "quiz", description = "Generate, take and grade quizzes.",
        subcommands = {QuizCommand.Generate.class, QuizCommand.ListQuizzes.class, QuizCommand.Show.class,
                QuizCommand.Take.class, QuizCommand.Grade.class, QuizCommand.Results.class, QuizCommand.Regrade.class,
                QuizCommand.Dispute.class})
public class QuizCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @ParentCommand
    StudyAgentCli root;

    /** The student's view of a question: it has no answer key. */
    static Map<String, Object> questionJson(Question q) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", q.getId());
        json.put("type", q.getType().code());
        json.put("stem", q.getStem());
        if (q instanceof MultipleChoiceQuestion mcq) {
            json.put("options", mcq.getOptions());
        }
        json.put("difficulty", q.getDifficulty().name().toLowerCase());
        json.put("conceptId", q.getConceptId());
        json.put("sourcePage", q.getSourcePage());
        return json;
    }

    static Map<String, Object> quizJson(Quiz quiz) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", quiz.getId());
        json.put("courseId", quiz.getCourseId());
        json.put("createdAt", quiz.getCreatedAt());
        json.put("difficulty", quiz.getDifficulty().name().toLowerCase());
        json.put("questions", quiz.getQuestions().stream().map(QuizCommand::questionJson).toList());
        return json;
    }

    static Map<String, Object> attemptJson(Quiz quiz, QuizAttempt attempt) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("attemptId", attempt.getId());
        json.put("quizId", attempt.getQuizId());
        json.put("status", attempt.getStatus().name());
        json.put("submittedAt", attempt.getSubmittedAt());
        json.put("totalScore", attempt.getTotalScore());
        json.put("maxScore", attempt.getMaxScore());
        json.put("percentage", attempt.getPercentage());
        List<Map<String, Object>> results = new ArrayList<>();
        for (Question q : quiz.getQuestions()) {
            GradeResult r = attempt.resultFor(q.getId()).orElseThrow();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("questionId", q.getId());
            result.put("answer", attempt.answerFor(q.getId()));
            result.put("correctAnswer", q.correctAnswerText());
            result.put("score", r.score());
            result.put("correct", r.correct());
            result.put("feedback", r.feedback());
            result.put("gradedBy", r.gradedByAI() ? "ai" : "rule");
            result.put("pending", r.pending());
            result.put("overridden", r.overridden());
            results.add(result);
        }
        json.put("results", results);
        return json;
    }

    /** The question as printed for the student, with its options. */
    private static String questionText(int number, Question q) {
        StringBuilder text = new StringBuilder(number + ". " + q.getStem());
        if (q instanceof MultipleChoiceQuestion mcq) {
            for (int i = 0; i < mcq.getOptions().size(); i++) {
                text.append("\n   ").append(MultipleChoiceQuestion.letter(i)).append(") ").append(mcq.getOptions().get(i));
            }
        }
        return text.toString();
    }

    private static String answerHint(Question q) {
        return switch (q.getType()) {
            case MULTIPLE_CHOICE -> "Answer (A-D, Enter to skip): ";
            case TRUE_FALSE -> "Answer (t/f, Enter to skip): ";
            case SHORT_ANSWER -> "Answer (one line, Enter to skip): ";
        };
    }

    static String resultsText(Quiz quiz, QuizAttempt attempt) {
        StringBuilder text = new StringBuilder(String.format("Attempt #%d of quiz #%d: %.1f / %d (%.0f%%)",
                attempt.getId(), quiz.getId(), attempt.getTotalScore(), attempt.getMaxScore(), attempt.getPercentage()));
        int number = 1;
        for (Question q : quiz.getQuestions()) {
            GradeResult r = attempt.resultFor(q.getId()).orElseThrow();
            String mark = r.pending() ? "PENDING" : r.correct() ? "RIGHT" : "WRONG";
            text.append(String.format("%n%n%d. [%s %.2f] %s", number++, mark, r.score(), q.getStem()));
            String answer = attempt.answerFor(q.getId());
            text.append("\n   Your answer: ").append(answer.isEmpty() ? "(none)" : answer);
            text.append("\n   Correct answer: ").append(q.correctAnswerText());
            if (!r.feedback().isEmpty()) {
                text.append("\n   ").append(r.feedback().replace("\n", "\n   "));
            }
            text.append("\n   Source: p. ").append(q.getSourcePage());
            if (r.overridden()) {
                text.append(" (grade set by you)");
            }
        }
        if (attempt.getStatus() == AttemptStatus.PENDING_GRADING) {
            text.append("\n\nSome answers are waiting to be graded. Try again with: studyagent quiz regrade ")
                    .append(attempt.getId());
        }
        return text.toString();
    }

    @Command(name = "generate", description = "Generate a quiz from course materials or concepts.")
    static class Generate implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Option(names = "--count", defaultValue = "10", description = "Number of questions, 1-30 (default ${DEFAULT-VALUE}).")
        int count;

        @Option(names = "--types", split = ",", defaultValue = "mcq,tf,short",
                description = "Question types: mcq, tf, short (default ${DEFAULT-VALUE}).")
        List<String> types;

        @Option(names = "--difficulty", defaultValue = "medium",
                description = "easy, medium or hard (default ${DEFAULT-VALUE}).")
        String difficulty;

        @Option(names = "--material", split = ",", description = "Only these material ids.")
        List<Long> materialIds = List.of();

        @Option(names = "--concept", split = ",", description = "Only these concept ids.")
        List<Long> conceptIds = List.of();

        @Override
        public Integer call() {
            Course c = parent.root.facade().findCourse(course);
            Set<QuestionType> questionTypes = EnumSet.noneOf(QuestionType.class);
            types.forEach(type -> questionTypes.add(QuestionType.fromCode(type)));
            Quiz quiz = parent.root.facade().generateQuiz(new QuizRequest(c.getId(), materialIds, conceptIds, count,
                    questionTypes, Difficulty.fromString(difficulty)));
            String text = "Created quiz #" + quiz.getId() + " with " + quiz.size() + " question(s). Take it with: "
                    + "studyagent quiz take " + quiz.getId();
            if (quiz.size() < count) {
                text += "\nNote: only " + quiz.size() + " of the " + count + " requested questions could be generated "
                        + "from this material.";
            }
            Map<String, Object> json = quizJson(quiz);
            json.put("requested", count);
            parent.root.printResult(json, text);
            return 0;
        }
    }

    @Command(name = "list", description = "List the quizzes of a course with their latest score.")
    static class ListQuizzes implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Override
        public Integer call() {
            Course c = parent.root.facade().findCourse(course);
            List<Quiz> quizzes = parent.root.facade().listQuizzes(c.getId());
            List<QuizAttempt> attempts = parent.root.facade().listAttempts(c.getId());
            List<Map<String, Object>> json = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Quiz quiz : quizzes) {
                List<QuizAttempt> own = attempts.stream().filter(a -> a.getQuizId() == quiz.getId()).toList();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", quiz.getId());
                row.put("createdAt", quiz.getCreatedAt());
                row.put("difficulty", quiz.getDifficulty().name().toLowerCase());
                row.put("questions", quiz.size());
                row.put("attempts", own.size());
                row.put("latestAttemptId", own.isEmpty() ? null : own.get(0).getId());
                row.put("latestPercentage", own.isEmpty() ? null : own.get(0).getPercentage());
                json.add(row);
                text.append(String.format("#%-5d %s  %-8s %2d question(s)  %d attempt(s)%s%n", quiz.getId(),
                        TIME.format(quiz.getCreatedAt()), row.get("difficulty"), quiz.size(), own.size(),
                        own.isEmpty() ? "" : String.format("  latest %.0f%% (attempt #%d)",
                                own.get(0).getPercentage(), own.get(0).getId())));
            }
            parent.root.printResult(json, quizzes.isEmpty()
                    ? "No quizzes yet. Create one with: studyagent quiz generate --course " + course
                    : text.toString().stripTrailing());
            return 0;
        }
    }

    @Command(name = "show", description = "Show a quiz's questions, without the answers.")
    static class Show implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Parameters(index = "0", description = "Quiz id.")
        long id;

        @Override
        public Integer call() {
            Quiz quiz = parent.root.facade().getQuiz(id);
            StringBuilder text = new StringBuilder("Quiz #" + id);
            List<Question> questions = quiz.getQuestions();
            for (int i = 0; i < questions.size(); i++) {
                text.append("\n\n").append(questionText(i + 1, questions.get(i)));
            }
            parent.root.printResult(quizJson(quiz), text.toString());
            return 0;
        }
    }

    @Command(name = "take", description = "Answer a quiz question by question; it is graded when you finish.")
    static class Take implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Parameters(index = "0", description = "Quiz id.")
        long id;

        @Override
        public Integer call() throws IOException {
            Quiz quiz = parent.root.facade().getQuiz(id);
            QuizAttempt attempt = parent.root.facade().startAttempt(id);
            List<Question> questions = quiz.getQuestions();
            for (int i = 0; i < questions.size(); i++) {
                Question question = questions.get(i);
                parent.root.prompt(questionText(i + 1, question) + "\n" + answerHint(question));
                String answer = parent.root.readLine();
                attempt.answer(question.getId(), answer == null ? "" : answer);
            }
            parent.root.prompt("Grading...\n");
            QuizAttempt graded = parent.root.facade().submitQuiz(attempt);
            parent.root.printResult(attemptJson(quiz, graded), resultsText(quiz, graded));
            return 0;
        }
    }

    @Command(name = "grade", description = "Grade prepared answers from a JSON file: "
            + "{\"quizId\": 7, \"answers\": {\"12\": \"A\", \"13\": \"true\"}}.")
    static class Grade implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Option(names = "--attempt", required = true, description = "JSON file with the quiz id and answers.")
        Path file;

        @Override
        public Integer call() throws IOException {
            JsonNode json = new ObjectMapper().readTree(Files.readString(file));
            if (json == null || !json.path("quizId").canConvertToLong() || !json.path("answers").isObject()) {
                throw new IllegalArgumentException("The file must look like {\"quizId\": 7, \"answers\": {\"12\": \"A\"}}.");
            }
            long quizId = json.get("quizId").asLong();
            QuizAttempt attempt = parent.root.facade().startAttempt(quizId);
            for (Map.Entry<String, JsonNode> answer : json.get("answers").properties()) {
                attempt.answer(parseQuestionId(answer.getKey()), answer.getValue().asText());
            }
            Quiz quiz = parent.root.facade().getQuiz(quizId);
            QuizAttempt graded = parent.root.facade().submitQuiz(attempt);
            parent.root.printResult(attemptJson(quiz, graded), resultsText(quiz, graded));
            return 0;
        }

        private static long parseQuestionId(String key) {
            try {
                return Long.parseLong(key);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + key + "\" is not a question id.");
            }
        }
    }

    @Command(name = "results", description = "Show the results of an attempt.")
    static class Results implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Parameters(index = "0", description = "Attempt id.")
        long id;

        @Override
        public Integer call() {
            QuizAttempt attempt = parent.root.facade().getAttempt(id);
            Quiz quiz = parent.root.facade().getQuiz(attempt.getQuizId());
            parent.root.printResult(attemptJson(quiz, attempt), resultsText(quiz, attempt));
            return 0;
        }
    }

    @Command(name = "regrade", description = "Grade the answers that are still pending.")
    static class Regrade implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Parameters(index = "0", description = "Attempt id.")
        long id;

        @Override
        public Integer call() {
            QuizAttempt attempt = parent.root.facade().regradePending(id);
            Quiz quiz = parent.root.facade().getQuiz(attempt.getQuizId());
            parent.root.printResult(attemptJson(quiz, attempt), resultsText(quiz, attempt));
            return 0;
        }
    }

    @Command(name = "dispute", description = "Replace an AI-assigned grade with your own.")
    static class Dispute implements Callable<Integer> {
        @ParentCommand
        QuizCommand parent;

        @Parameters(index = "0", description = "Attempt id.")
        long id;

        @Option(names = "--question", required = true, description = "Question id.")
        long questionId;

        @Option(names = "--score", required = true, description = "New score, from 0 to 1.")
        double score;

        @Override
        public Integer call() {
            QuizAttempt attempt = parent.root.facade().overrideGrade(id, questionId, score);
            Quiz quiz = parent.root.facade().getQuiz(attempt.getQuizId());
            parent.root.printResult(attemptJson(quiz, attempt), resultsText(quiz, attempt));
            return 0;
        }
    }
}
