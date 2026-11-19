package shelf;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validated;
import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// k37's program over YAVI: a row as a JDBC driver hands it, its dates and times of
// java.sql, read by a schema of the java.time classes they stand for; a java.util.Date
// and a value of java.sql given for a class it is no column of are a mismatch. Written
// here over YAVI's public API.
public record Row(LocalDate due, LocalTime at, LocalDateTime taken, Instant stamped) {

    public static final ObjectInputValidator<Row> validator = Yavi.schema()
        ._localDate("due", c -> c.after(() -> LocalDate.of(2020, 1, 1)))
        ._localTime("at", c -> c.between(() -> LocalTime.of(9, 0), () -> LocalTime.of(17, 30)))
        ._localDateTime("taken")
        ._instant("stamped")
        .fromMap(Row::new);

    static Map<String, Object> input() {
        Map<String, Object> input = new HashMap<>();
        input.put("due", java.sql.Date.valueOf("2026-12-24"));
        input.put("at", Time.valueOf("10:15:00"));
        input.put("taken", Timestamp.valueOf("2026-11-20 11:59:59.123456789"));
        input.put("stamped", Timestamp.valueOf("2026-11-20 02:59:59.5"));
        return input;
    }

    static void show(String property, Object value) {
        Map<String, Object> input = input();
        if (property != null) {
            input.put(property, value);
        }
        Validated<Row> validated = validator.validate(input);
        System.out.println(property + " " + value + ": " + validated.isValid());
        if (validated.isValid()) {
            System.out.println(validated.value());
        }
        else {
            ConstraintViolations violations = validated.errors();
            violations.forEach(violation -> System.out.println("  " + violation.message()));
        }
    }

    public static void main(String[] args) {
        show(null, null);
        show("due", java.sql.Date.valueOf("2019-12-31"));
        show("due", java.sql.Date.valueOf("1582-10-10"));
        show("at", Time.valueOf("08:59:59"));
        show("at", Time.valueOf("25:00:00"));
        show("taken", Timestamp.valueOf("1582-10-04 23:59:59.9"));
        // a value of java.sql that stands for no column of the class, and a plain date
        show("due", Timestamp.valueOf("2026-12-24 00:00:00"));
        show("due", new java.util.Date(0));
        show("at", java.sql.Date.valueOf("2026-12-24"));
        show("taken", java.sql.Date.valueOf("2026-12-24"));
        show("stamped", java.sql.Date.valueOf("2026-12-24"));
        show("stamped", new java.util.Date(0));
        // texts and values of java.time are read as before
        show("due", "2027-01-02");
        show("stamped", Instant.EPOCH);
    }
}
