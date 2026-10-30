package studybuddy.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Observer pattern subject: services publish {@link StudyEvent}s here and every subscribed
 * {@link StudyEventListener} is notified. Services therefore never call GUI code directly.
 *
 * Events are delivered on the publishing thread; Swing listeners move to the event-dispatch thread
 * themselves. A failing listener is logged and does not stop delivery to the others.
 */
public class EventBus {

    private static final Logger LOG = Logger.getLogger(EventBus.class.getName());

    private final List<StudyEventListener> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(StudyEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(StudyEventListener listener) {
        listeners.remove(listener);
    }

    public void publish(StudyEvent event) {
        for (StudyEventListener listener : listeners) {
            try {
                listener.onStudyEvent(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Listener failed for " + event.type(), e);
            }
        }
    }
}
