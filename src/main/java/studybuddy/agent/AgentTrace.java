package studybuddy.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import studybuddy.domain.TraceStep;

/** The steps taken for one message (F12). The listener hears each step start and finish, for live display. */
public class AgentTrace {

    private final List<TraceStep> steps = new ArrayList<>();
    private final Consumer<TraceStep> listener;

    public AgentTrace(Consumer<TraceStep> listener) {
        this.listener = listener;
    }

    /** Records that a step started and returns its number. */
    int startStep(String tool, String description, Map<String, Object> arguments) {
        int number = steps.size() + 1;
        TraceStep step = new TraceStep(number, tool, description, arguments, "", TraceStep.Status.RUNNING, 0);
        steps.add(step);
        listener.accept(step);
        return number;
    }

    /** Records how a started step ended. */
    void finishStep(int number, String result, boolean success, long millis) {
        TraceStep started = steps.get(number - 1);
        TraceStep finished = new TraceStep(number, started.tool(), started.description(), started.arguments(), result,
                success ? TraceStep.Status.SUCCEEDED : TraceStep.Status.FAILED, millis);
        steps.set(number - 1, finished);
        listener.accept(finished);
    }

    public List<TraceStep> getSteps() {
        return List.copyOf(steps);
    }

    public int size() {
        return steps.size();
    }
}
