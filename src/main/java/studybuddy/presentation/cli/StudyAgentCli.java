package studybuddy.presentation.cli;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import studybuddy.application.ApplicationFactory;
import studybuddy.application.StudyAgentFacade;
import studybuddy.config.AppConfig;

/**
 * Root of the {@code studyagent} command line. Every subcommand talks only to the
 * {@link StudyAgentFacade}. With {@code --json}, results and errors are printed as JSON
 * (used by the KUMA test harness in Stage 3); otherwise as readable text.
 */
@Command(name = "studyagent", mixinStandardHelpOptions = true, version = "StudyBuddy 0.1",
        description = "StudyBuddy: an AI study and learning agent for your course materials.",
        subcommands = {CourseCommand.class, MaterialCommand.class, AskCommand.class})
public class StudyAgentCli implements Runnable {

    @Option(names = "--json", scope = CommandLine.ScopeType.INHERIT,
            description = "Print machine-readable JSON instead of text.")
    boolean jsonOutput;

    private final Supplier<StudyAgentFacade> facadeFactory;
    private StudyAgentFacade facade;
    private PrintWriter out;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** @param facadeFactory opens the facade on first use, so --help works without touching the database */
    public StudyAgentCli(Supplier<StudyAgentFacade> facadeFactory) {
        this.facadeFactory = facadeFactory;
    }

    public static void main(String[] args) {
        StudyAgentCli cli = new StudyAgentCli(() -> ApplicationFactory.create(AppConfig.load()));
        System.exit(cli.execute(args));
    }

    /** Runs one command line and returns its exit code (0 = success). */
    public int execute(String[] args) {
        return execute(args, new PrintWriter(System.out, true));
    }

    /** Runs one command line, writing all output (including errors) to the given writer. */
    public int execute(String[] args, PrintWriter writer) {
        CommandLine commandLine = new CommandLine(this);
        commandLine.setOut(writer);
        commandLine.setErr(writer);
        out = writer;
        commandLine.setExecutionExceptionHandler((e, cmd, parsed) -> {
            printError(e);
            return 1;
        });
        try {
            return commandLine.execute(args);
        } finally {
            writer.flush();
            if (facade != null) {
                facade.close();
                facade = null;
            }
        }
    }

    @Override
    public void run() {
        out.println("Use a subcommand, e.g. 'studyagent course list'. See 'studyagent --help'.");
    }

    StudyAgentFacade facade() {
        if (facade == null) {
            facade = facadeFactory.get();
        }
        return facade;
    }

    /** Prints jsonResult as JSON when --json is given, otherwise the text. */
    void printResult(Object jsonResult, String text) {
        if (jsonOutput) {
            try {
                out.println(mapper.writeValueAsString(jsonResult));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not write JSON output", e);
            }
        } else {
            out.println(text);
        }
        out.flush();
    }

    private void printError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message);
        printResult(error, "Error: " + message);
    }
}
