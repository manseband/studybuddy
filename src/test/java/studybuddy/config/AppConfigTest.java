package studybuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    @TempDir
    Path dir;

    @Test
    void defaultsComeFromPropertiesFile() {
        AppConfig config = AppConfig.load(dir.resolve("missing.env"), Map.of());

        assertEquals("gemini-3.1-flash-lite", config.routineModel());
        assertEquals("gemini-3.8-flash", config.coachModel());
        assertEquals("gemini-embedding-001", config.embeddingModel());
        assertEquals(400, config.chunkWords());
        assertEquals(50, config.chunkOverlap());
        assertEquals(8, config.agentMaxSteps());
        assertEquals(20L * 1024 * 1024, config.maxFileBytes());
        assertFalse(config.hasApiKey());
    }

    @Test
    void dotEnvOverridesDefaultsAndEnvironmentOverridesDotEnv() throws IOException {
        Path env = Files.writeString(dir.resolve(".env"), """
                # comment
                export GEMINI_API_KEY="from-dotenv"
                STUDYBUDDY_RETRIEVAL_TOP_K=3
                STUDYBUDDY_DB_PATH='custom.db'
                """);

        AppConfig config = AppConfig.load(env, Map.of("GEMINI_API_KEY", "from-environment"));

        assertEquals("from-environment", config.apiKey().orElseThrow());
        assertEquals(3, config.retrievalTopK());
        assertEquals(Path.of("custom.db"), config.databasePath());
    }

    @Test
    void placeholderKeyCountsAsMissing() throws IOException {
        Path env = Files.writeString(dir.resolve(".env"), "GEMINI_API_KEY=YOUR_LOCAL_API_KEY_HERE\n");

        assertFalse(AppConfig.load(env, Map.of()).hasApiKey());
    }

    @Test
    void toStringNeverContainsTheKey() {
        AppConfig config = AppConfig.load(dir.resolve("none"), Map.of("GEMINI_API_KEY", "secret-value-123"));

        assertTrue(config.hasApiKey());
        assertFalse(config.toString().contains("secret-value-123"));
    }

    @Test
    void overrideNameMapsDotsToUnderscores() {
        assertEquals("STUDYBUDDY_DB_PATH", AppConfig.overrideName("db.path"));
    }
}
