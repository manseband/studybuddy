package studybuddy.persistence;

import java.util.List;
import java.util.Optional;

/**
 * Generic storage for one aggregate type. Services depend on this interface, not on SQLite,
 * so tests can use an in-memory database and the storage technology can change.
 *
 * @param <T> the aggregate root (Course, Material, Quiz, ...)
 */
public interface Repository<T> {

    /** Inserts the entity if its id is 0, otherwise updates it. Returns the entity with its id set. */
    T save(T entity);

    Optional<T> findById(long id);

    /** All entities belonging to the course. */
    List<T> findByCourse(long courseId);

    void delete(long id);
}
