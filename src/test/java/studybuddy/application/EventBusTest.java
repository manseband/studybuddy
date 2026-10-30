package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class EventBusTest {

    @Test
    void deliversEventsToEverySubscriberOnce() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        StudyEventListener first = e -> received.add("first:" + e.type());
        bus.subscribe(first);
        bus.subscribe(first);
        bus.subscribe(e -> received.add("second:" + e.courseId()));

        bus.publish(StudyEvent.of(StudyEvent.Type.MATERIAL_ADDED, 7, "notes.pdf added"));

        assertEquals(List.of("first:MATERIAL_ADDED", "second:7"), received);
    }

    @Test
    void failingListenerDoesNotBlockOthers() {
        Logger.getLogger(EventBus.class.getName()).setLevel(Level.OFF);
        EventBus bus = new EventBus();
        List<StudyEvent> received = new ArrayList<>();
        bus.subscribe(e -> {
            throw new IllegalStateException("broken panel");
        });
        bus.subscribe(received::add);

        bus.publish(StudyEvent.of(StudyEvent.Type.QUIZ_SUBMITTED, 1, "quiz graded"));

        assertEquals(1, received.size());
    }

    @Test
    void unsubscribedListenersStopReceiving() {
        EventBus bus = new EventBus();
        List<StudyEvent> received = new ArrayList<>();
        StudyEventListener listener = received::add;
        bus.subscribe(listener);
        bus.unsubscribe(listener);

        bus.publish(StudyEvent.of(StudyEvent.Type.COURSE_CHANGED, 1, "renamed"));

        assertEquals(0, received.size());
    }
}
