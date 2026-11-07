package studybuddy.ai;

/**
 * Description of a tool the model may call (F09).
 * parameterSchema is a JSON Schema object, e.g. {"type":"object","properties":{"courseId":{"type":"integer"}}}.
 */
public record ToolSpec(String name, String description, String parameterSchema) {
}
