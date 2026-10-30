package studybuddy.presentation.cli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import studybuddy.application.DuplicateMaterialException;
import studybuddy.application.MaterialService;
import studybuddy.domain.Course;
import studybuddy.domain.Material;

/** {@code studyagent material add|list|rename|remove} (F01). */
@Command(name = "material", description = "Upload and manage course materials (PDF, TXT, MD).",
        subcommands = {MaterialCommand.Add.class, MaterialCommand.ListMaterials.class, MaterialCommand.Rename.class,
                MaterialCommand.Remove.class, MaterialCommand.Reindex.class})
public class MaterialCommand {

    @ParentCommand
    StudyAgentCli root;

    static Map<String, Object> toJson(Material m) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", m.getId());
        json.put("courseId", m.getCourseId());
        json.put("title", m.getTitle());
        json.put("type", m.getFileType());
        json.put("pages", m.getPageCount());
        json.put("words", m.getWordCount());
        json.put("chunks", m.getChunkCount());
        json.put("status", m.getStatus().label());
        return json;
    }

    @Command(name = "add", description = "Upload a material, e.g. material add --course EECS3311 notes.pdf")
    static class Add implements Callable<Integer> {
        @ParentCommand
        MaterialCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Parameters(index = "0", description = "PDF, TXT or MD file.")
        Path file;

        @Option(names = "--replace", description = "Replace an identical file already in the course.")
        boolean replace;

        @Override
        public Integer call() {
            StudyAgentCli root = parent.root;
            Course c = root.facade().findCourse(course);
            Material m;
            try {
                m = root.facade().uploadMaterial(c.getId(), file, replace);
            } catch (DuplicateMaterialException e) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("error", e.getMessage());
                json.put("existing", toJson(e.getExisting()));
                root.printResult(json, e.getMessage() + ". Skipped. Run again with --replace to replace it.");
                return 3;
            }
            String text = "Added \"" + m.getTitle() + "\" to " + c.getName() + " (material #" + m.getId() + "): "
                    + m.getPageCount() + " page(s), " + m.getWordCount() + " words, " + m.getChunkCount() + " chunks.";
            if (m.getChunkCount() == 0) {
                text += "\nNo text was found (scanned PDFs need OCR, which is not supported). The material was saved "
                        + "but cannot be searched.";
            }
            root.printResult(toJson(m), text);
            return 0;
        }
    }

    @Command(name = "list", description = "List the materials of a course.")
    static class ListMaterials implements Callable<Integer> {
        @ParentCommand
        MaterialCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Override
        public Integer call() {
            Course c = parent.root.facade().findCourse(course);
            List<Material> materials = parent.root.facade().listMaterials(c.getId());
            StringBuilder text = new StringBuilder();
            if (materials.isEmpty()) {
                text.append("No materials in ").append(c.getName())
                    .append(" yet. Upload one with: studyagent material add --course \"")
                    .append(c.getName()).append("\" FILE");
            } else {
                text.append(String.format("%-5s %-30s %-5s %6s %7s %7s  %s%n", "ID", "TITLE", "TYPE", "PAGES", "WORDS",
                        "CHUNKS", "STATUS"));
            }
            for (Material m : materials) {
                text.append(String.format("#%-4d %-30s %-5s %6d %7d %7d  %s%n", m.getId(), m.getTitle(), m.getFileType(),
                        m.getPageCount(), m.getWordCount(), m.getChunkCount(), m.getStatus().label()));
            }
            parent.root.printResult(materials.stream().map(MaterialCommand::toJson).toList(),
                    text.toString().stripTrailing());
            return 0;
        }
    }

    @Command(name = "rename", description = "Rename a material.")
    static class Rename implements Callable<Integer> {
        @ParentCommand
        MaterialCommand parent;

        @Parameters(index = "0", description = "Material id.")
        long id;

        @Parameters(index = "1", description = "New title.")
        String title;

        @Override
        public Integer call() {
            Material m = parent.root.facade().renameMaterial(id, title);
            parent.root.printResult(toJson(m), "Renamed material #" + m.getId() + " to \"" + m.getTitle() + "\"");
            return 0;
        }
    }

    @Command(name = "remove", description = "Remove a material and its search index entries.")
    static class Remove implements Callable<Integer> {
        @ParentCommand
        MaterialCommand parent;

        @Parameters(index = "0", description = "Material id.")
        long id;

        @Override
        public Integer call() {
            parent.root.facade().removeMaterial(id);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("removed", true);
            parent.root.printResult(json, "Removed material #" + id);
            return 0;
        }
    }

    @Command(name = "reindex", description = "Retry embeddings for chunks that have none (e.g. after a rate limit).")
    static class Reindex implements Callable<Integer> {
        @ParentCommand
        MaterialCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Override
        public Integer call() {
            StudyAgentCli root = parent.root;
            Course c = root.facade().findCourse(course);
            if (!root.facade().isAiAvailable()) {
                root.printResult(Map.of("courseId", c.getId(), "error", "No API key configured"),
                        "No Gemini API key is configured, so search uses keywords only. Nothing to reindex.");
                return 1;
            }
            MaterialService.EmbeddingRefresh result = root.facade().refreshEmbeddings(c.getId());
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("courseId", c.getId());
            json.put("chunks", result.chunks());
            json.put("created", result.created());
            json.put("stillMissing", result.missingAfter());
            String text = result.missingBefore() == 0
                    ? "All " + result.chunks() + " chunks of " + c.getName() + " already have embeddings."
                    : "Created embeddings for " + result.created() + " of " + result.missingBefore() + " chunk(s).";
            if (result.missingAfter() > 0) {
                text += "\n" + result.missingAfter() + " chunk(s) still have none (the embedding quota may be used up). "
                        + "They remain searchable by keyword; try again in a minute.";
            }
            root.printResult(json, text);
            return 0;
        }
    }
}
