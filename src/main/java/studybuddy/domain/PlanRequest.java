package studybuddy.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parameters for a study plan (F11). hoursPerDay holds the available hours for each weekday
 * (missing days mean no study time). Whether the exam date is in the future is checked by
 * StudyPlanService, since it depends on today's date.
 */
public record PlanRequest(long courseId, LocalDate examDate, Map<DayOfWeek, Double> hoursPerDay,
                          Set<LocalDate> daysOff, List<Long> conceptIds) {

    public PlanRequest {
        Objects.requireNonNull(examDate, "exam date is required");
        Map<DayOfWeek, Double> hours = new EnumMap<>(DayOfWeek.class);
        hoursPerDay.forEach((day, h) -> {
            if (h == null || h < 0 || h > 24) {
                throw new IllegalArgumentException("Hours for " + day + " must be between 0 and 24");
            }
            hours.put(day, h);
        });
        hoursPerDay = Map.copyOf(hours);
        daysOff = Set.copyOf(daysOff);
        conceptIds = List.copyOf(conceptIds);
    }

    public double weeklyHours() {
        return hoursPerDay.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** Available hours on a date, taking days off into account. */
    public double hoursOn(LocalDate date) {
        if (daysOff.contains(date)) {
            return 0;
        }
        return hoursPerDay.getOrDefault(date.getDayOfWeek(), 0.0);
    }
}
