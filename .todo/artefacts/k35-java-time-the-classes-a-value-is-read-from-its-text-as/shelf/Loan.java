package shelf;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.Map;

import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validated;
import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// k35's program over YAVI: a schema of the eight classes of java.time a value is read
// from its text as, with the temporal constraints (a bound, a range, the present of a
// clock, a field), given texts that are and are not one, values that already are, and
// numbers for the year. Written here over YAVI's public API.
public record Loan(LocalDate due, LocalTime at, LocalDateTime taken, OffsetDateTime logged, ZonedDateTime returned,
        Instant stamped, Year year, YearMonth month) {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.ofHours(9));

    public static final ObjectInputValidator<Loan> validator = Yavi.schema()
        ._localDate("due", c -> c.after(() -> LocalDate.of(2020, 1, 1))
            .future(CLOCK)
            .fieldPredicate(ChronoField.DAY_OF_WEEK, day -> day != DayOfWeek.SUNDAY.getValue()))
        ._localTime("at", c -> c.between(() -> LocalTime.of(9, 0), () -> LocalTime.of(17, 30)))
        ._localDateTime("taken", c -> c.pastOrPresent(CLOCK))
        ._offsetDateTime("logged", c -> c.beforeOrEqual(() -> OffsetDateTime.now(CLOCK)))
        ._zonedDateTime("returned", c -> c.afterOrEqual(() -> ZonedDateTime.now(CLOCK)))
        ._instant("stamped", c -> c.past(CLOCK))
        ._year("year", c -> c.before(() -> Year.of(2100)))
        ._yearMonth("month", c -> c.futureOrPresent(CLOCK))
        .fromMap(Loan::new);

    static Map<String, Object> input() {
        Map<String, Object> input = new HashMap<>();
        input.put("due", "2026-12-24");
        input.put("at", "10:15");
        input.put("taken", "2026-11-20T11:59:59.5");
        input.put("logged", "2026-11-20T12:00+09:00");
        input.put("returned", "2026-11-20T04:00+01:00[UTC+01:00]");
        input.put("stamped", "2026-11-20T02:59:59.999999999Z");
        input.put("year", "2026");
        input.put("month", "2026-11");
        return input;
    }

    static void show(String property, Object value) {
        Map<String, Object> input = input();
        if (property != null) {
            input.put(property, value);
        }
        Validated<Loan> validated = validator.validate(input);
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
        // texts that are no value of the type, and texts the constraints refuse
        show("due", "2026-02-30");
        show("due", "2026-12-27");
        show("due", "2019-12-31");
        show("due", "24 December");
        show("at", "08:59:59");
        show("at", "17:30");
        show("at", "25:00");
        show("taken", "2026-11-20T12:00:00.000000001");
        show("taken", "2026-11-20 12:00");
        show("logged", "2026-11-20T03:00:01Z");
        show("logged", "2026-11-20T12:00");
        show("returned", "2026-11-20T02:59:59Z");
        show("returned", "2026-11-20T12:00+09:00");
        show("returned", "2026-11-20T12:00+09:00[Nowhere/None]");
        show("stamped", "2026-11-20T03:00:00Z");
        show("stamped", "2026-11-20T12:00:00+09:00");
        show("stamped", "2026-11-20");
        show("year", "2100");
        show("year", "MMXXVI");
        show("month", "2026-10");
        show("month", "2026-13");
        // values that already are of the type
        show("due", LocalDate.of(2027, 1, 2));
        show("at", LocalTime.NOON);
        show("taken", LocalDateTime.of(2020, 2, 29, 0, 0));
        show("logged", OffsetDateTime.parse("2000-01-01T00:00Z"));
        show("returned", ZonedDateTime.parse("2030-01-01T00:00Z[UTC]"));
        show("stamped", Instant.EPOCH);
        show("year", Year.of(-44));
        show("month", YearMonth.of(2027, 1));
        show("due", LocalDateTime.of(2027, 1, 2, 3, 4));
        // a number is a year, where it is whole and a year
        show("year", 2026);
        show("year", 2026L);
        show("year", 2026.0);
        show("year", 2026.5);
        show("year", 1000000000);
        show("year", 2100);
        show("due", 20261224);
    }
}
