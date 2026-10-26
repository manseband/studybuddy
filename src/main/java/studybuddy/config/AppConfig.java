package studybuddy.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Application settings: defaults from {@code studybuddy.properties} on the classpath,
 * overridden by a local {@code .env} file, overridden in turn by real environment variables.
 *
 * The Gemini API key is read only from {@code GEMINI_API_KEY} (environment or .env).
 * It is never logged and never included in {@link #toString()}.
 */
public final class AppConfig {

    public static final String API_KEY_VARIABLE = "GEMINI_API_KEY";
    private static final String DEFAULTS_RESOURCE = "/studybuddy.properties";
    private static final String OVERRIDE_PREFIX = "STUDYBUDDY_";

    private final Properties defaults;
    private final Map<String, String> dotEnv;
    private final Map<String, String> environment;

    private AppConfig(Properties defaults, Map<String, String> dotEnv, Map<String, String> environment) {
        this.defaults = defaults;
        this.dotEnv = dotEnv;
        this.environment = environment;
    }

    /** Loads settings using {@code .env} in the working directory and the process environment. */
    public static AppConfig load() {
        return load(Path.of(".env"), System.getenv());
    }

    /** Loads settings from the given .env file (may be missing) and environment map. Used by tests. */
    public static AppConfig load(Path dotEnvFile, Map<String, String> environment) {
        return new AppConfig(loadDefaults(), readDotEnv(dotEnvFile), Map.copyOf(environment));
    }

    public Optional<String> apiKey() {
        String key = lookup(API_KEY_VARIABLE);
        if (key == null || key.isBlank() || key.equals("YOUR_LOCAL_API_KEY_HERE")) {
            return Optional.empty();
        }
        return Optional.of(key.trim());
    }

    public boolean hasApiKey() {
        return apiKey().isPresent();
    }

    public Path databasePath() {
        return Path.of(get("db.path"));
    }

    public String routineModel() {
        return get("llm.model.routine");
    }

    public String coachModel() {
        return get("llm.model.coach");
    }

    public String embeddingModel() {
        return get("llm.model.embedding");
    }

    public int llmMaxRetries() {
        return getInt("llm.max.retries");
    }

    public int llmTimeoutSeconds() {
        return getInt("llm.timeout.seconds");
    }

    public long maxFileBytes() {
        return getInt("ingest.max.file.mb") * 1024L * 1024L;
    }

    public int chunkWords() {
        return getInt("ingest.chunk.words");
    }

    public int chunkOverlap() {
        return getInt("ingest.chunk.overlap");
    }

    public int retrievalTopK() {
        return getInt("retrieval.top.k");
    }

    public double weakThreshold() {
        return getDouble("mastery.weak.threshold");
    }

    public int agentMaxSteps() {
        return getInt("agent.max.steps");
    }

    /** Returns the effective value of a property key, applying .env and environment overrides. */
    public String get(String key) {
        String value = lookup(overrideName(key));
        if (value == null) {
            value = defaults.getProperty(key);
        }
        if (value == null) {
            throw new IllegalStateException("Missing configuration value: " + key);
        }
        return value.trim();
    }

    public int getInt(String key) {
        try {
            return Integer.parseInt(get(key));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuration value " + key + " must be a whole number", e);
        }
    }

    public double getDouble(String key) {
        try {
            return Double.parseDouble(get(key));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuration value " + key + " must be a number", e);
        }
    }

    /** "db.path" -> "STUDYBUDDY_DB_PATH" */
    static String overrideName(String key) {
        return OVERRIDE_PREFIX + key.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    /** Environment variables win over .env entries. */
    private String lookup(String name) {
        String value = environment.get(name);
        return value != null ? value : dotEnv.get(name);
    }

    private static Properties loadDefaults() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " not found on the classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DEFAULTS_RESOURCE, e);
        }
        return props;
    }

    /** Parses KEY=VALUE lines; ignores blank lines and # comments; strips optional quotes and "export ". */
    static Map<String, String> readDotEnv(Path file) {
        Map<String, String> values = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return values;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2
                    && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(name, value);
        }
        return values;
    }

    @Override
    public String toString() {
        return "AppConfig[db=" + databasePath()
                + ", routineModel=" + routineModel()
                + ", coachModel=" + coachModel()
                + ", embeddingModel=" + embeddingModel()
                + ", apiKey=" + (hasApiKey() ? "set" : "not set") + "]";
    }
}
