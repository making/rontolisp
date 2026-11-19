package yavi;

import java.time.LocalDate;
import java.util.Map;

import am.ik.yavi.core.Validated;
import am.ik.yavi.core.ValueValidator;
import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// A booking read with a YAVI schema (am.ik.yavi:yavi) from an untyped input: the map a
// JSON body decodes to, then the strings a form sends. The record is made only of an
// input that has every property, of its type and within its limits.
public record Booking(String guest, Room room, LocalDate checkIn, Integer nights) {

    enum Room {

        SINGLE, TWIN, SUITE

    }

    static final LocalDate OPENING = LocalDate.of(2026, 12, 1);

    static final ObjectInputValidator<Booking> schema = Yavi.schema()
        ._string("guest", c -> c.notBlank().lessThanOrEqual(20))
        ._enum("room", Room.class, c -> c.notNull())
        ._localDate("checkIn", c -> c.notNull().afterOrEqual(() -> OPENING))
        ._integer("nights", c -> c.notNull().greaterThanOrEqual(1).lessThanOrEqual(14))
        .fromMap(Booking::new);

    static Map<String, Object> input(Object guest, Object room, Object checkIn, Object nights) {
        return Map.of("guest", guest, "room", room, "checkIn", checkIn, "nights", nights);
    }

    static void show(String label, Validated<Booking> booking) {
        if (booking.isValid()) {
            System.out.println(label + ": " + booking.value());
            return;
        }
        System.out.println(label + ": " + booking.errors().size() + " violations");
        booking.errors().forEach(violation -> System.out.println("  " + violation.message()));
    }

    public static void main(String[] args) {
        show("as sent", schema.validate(input("Ann", "TWIN", "2026-12-24", 3)));
        // a decoder's number is whatever it made of the digits: a long and a double are
        // read as the Integer asked for when they are one
        show("a long", schema.validate(input("Ken", "SUITE", "2027-01-02", 14L)));
        show("a double", schema.validate(input("Ken", "SUITE", "2027-01-02", 2.0)));
        show("a fraction", schema.validate(input("Ken", "SUITE", "2027-01-02", 2.5)));
        show("other types", schema.validate(input(7, "PENTHOUSE", "Christmas Eve", "three")));
        show("out of limits", schema.validate(input(" ", "SINGLE", "2026-11-30", 15)));
        show("nothing", schema.validate(Map.of()));
        // a form sends text only, and the same schema reads it
        ValueValidator<Map<String, ?>, Booking> form = schema.readingParameters();
        show("a form", form.validate(input("Ann", "SINGLE", "2026-12-31", "2")));
        show("a form's typo", form.validate(input("Ann", "SINGLE", "2026-12-32", "2 nights")));
    }
}
