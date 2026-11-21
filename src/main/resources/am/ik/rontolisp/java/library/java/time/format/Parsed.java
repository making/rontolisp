// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What a formatter read: the fields, made a date and a time where they make one, and the
// zone. The class has the name a JDK's has, which a refusal shows ("of type
// java.time.format.Parsed"). How fields are made a date and a time in each resolver
// style, in which order they are refused and in which words, and which fields stay are
// a JDK 25's answers to a grid (.kb/java-frontend.md, "java.time: the formatter"):
// a year of an era is a year, then a date of the year, month and day (or of the day of
// the year, or of a week and a day), then the fields of a time of day, which ones make a
// time, and every field left is checked against what was made. A formatter of a calendar
// other than ISO's, in a program with a calendar of its own, has the calendar make the date
// (its resolveDate over a map of the fields) and refuses a date of another calendar, an
// instant made the calendar's zoned date-time and days a time passed added as an ISO
// period, which such a date refuses (a JDK 25's answers).
package java.time.format;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.ChronoLocalDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class Parsed implements TemporalAccessor {

    static final TemporalQuery<Period> EXCESS_DAYS = temporal -> temporal instanceof Parsed parsed
            ? Period.ofDays((int) parsed.excessDays) : Period.ZERO;

    static final TemporalQuery<Boolean> LEAP_SECOND = temporal -> temporal instanceof Parsed parsed
            && parsed.leapSecond;

    private static final long NANOS_PER_SECOND = 1000000000L;

    private static final long NANOS_PER_DAY = 86400L * NANOS_PER_SECOND;

    private TemporalField[] fields;

    private long[] values;

    private int count;

    private ZoneId zone;

    /** The time a zone's name in words was of: 0 standard, 1 daylight, 2 generic; -1 none. */
    private final int zoneTime;

    /** A period of the day read and not yet resolved: its first minute and the one after its last. */
    private int[] period;

    private LocalDate date;

    /**
     * The formatter's calendar, in a program with a calendar of its own (Texts.ownCalendars());
     * null for none.
     */
    private Chronology chronology;

    /** The date the formatter's calendar made, where that calendar is not ISO's: date is then null. */
    private ChronoLocalDate calendarDate;

    private LocalTime time;

    private long excessDays;

    private boolean leapSecond;

    private ResolverStyle style;

    /** Whether the program can ask for a calendar: answered by the compiler, as Iso's. */
    static native boolean calendars();

    /** Whether the program makes the part that reads a day's period: answered by the compiler. */
    static native boolean periods();

    /** Whether the program makes the part that reads a zone's name in words: answered by the compiler. */
    static native boolean zoneTexts();

    Parsed(Reading in) {
        this.fields = java.util.Arrays.copyOf(in.fields, in.count + 4);
        this.values = java.util.Arrays.copyOf(in.values, in.count + 4);
        this.count = in.count;
        this.zone = in.zone;
        this.zoneTime = in.zoneTime;
        this.period = in.period;
        this.leapSecond = in.leapSecond;
        if (Texts.ownCalendars()) {
            this.chronology = in.chronology;
        }
    }

    /** Whether the formatter's calendar is one other than ISO's: asked in a program with one. */
    private boolean otherCalendar() {
        return chronology != null && chronology != IsoChronology.INSTANCE;
    }

    /**
     * The date fields made a date, in a program with a calendar of its own: by ISO's rules
     * over the arrays, or by the formatter's calendar where it is another.
     */
    private void dates() {
        if (otherCalendar()) {
            ChronoLocalDate made = chronology.resolveDate(new Fields(), style);
            if (made != null) {
                dated(made);
            }
            return;
        }
        resolveDate();
    }

    /** A date of the formatter's calendar, or of ISO's where it has none: one of another is refused. */
    private void dated(ChronoLocalDate made) {
        Chronology effective = chronology != null ? chronology : IsoChronology.INSTANCE;
        if (!effective.equals(made.getChronology())) {
            throw new DateTimeException("ChronoLocalDate must use the effective parsed chronology: " + effective);
        }
        if (!otherCalendar()) {
            date(LocalDate.from(made));
            return;
        }
        if (calendarDate != null && !calendarDate.equals(made)) {
            throw new DateTimeException("Conflict found: Fields resolved to two different dates: " + calendarDate + " "
                    + made);
        }
        calendarDate = made;
    }

    private int index(TemporalField field) {
        for (int i = 0; i < count; i++) {
            if (fields[i] == field) {
                return i;
            }
        }
        return -1;
    }

    private boolean has(TemporalField field) {
        return index(field) >= 0;
    }

    private long valueOf(TemporalField field) {
        return values[index(field)];
    }

    private long remove(TemporalField field) {
        int index = index(field);
        long value = values[index];
        for (int i = index; i < count - 1; i++) {
            fields[i] = fields[i + 1];
            values[i] = values[i + 1];
        }
        count--;
        return value;
    }

    /** A field made of others; the one read first wins, another value is a conflict. */
    private void put(TemporalField field, long value, TemporalField resolving) {
        int index = index(field);
        if (index >= 0) {
            if (values[index] != value) {
                throw new DateTimeException("Conflict found: " + field.toString() + " " + values[index] + " differs from "
                        + field.toString() + " " + value + (resolving == null ? "" : " while resolving  " + resolving.toString()));
            }
            return;
        }
        append(field, value);
    }

    private void append(TemporalField field, long value) {
        if (count == fields.length) {
            fields = java.util.Arrays.copyOf(fields, count * 2);
            values = java.util.Arrays.copyOf(values, count * 2);
        }
        fields[count] = field;
        values[count] = value;
        count++;
    }

    private void date(LocalDate made) {
        if (date != null && !date.equals(made)) {
            throw new DateTimeException("Conflict found: Fields resolved to two different dates: " + date + " " + made);
        }
        date = made;
    }

    private void time(LocalTime made) {
        if (time != null && !time.equals(made)) {
            throw new DateTimeException("Conflict found: Fields resolved to different times: " + time + " " + made);
        }
        time = made;
    }

    /**
     * Whether the program makes a field that is no ChronoField -- of IsoFields,
     * JulianFields, WeekFields or its own --: answered by the compiler, so a program
     * making none has nothing of resolving one.
     */
    static native boolean others();

    /**
     * Whether the program can name the fields to resolve: answered by whether it reaches
     * DateTimeFormatter.withResolverFields, so that a program that cannot has nothing of
     * cutting them.
     */
    static native boolean filtered();

    /**
     * Whether a formatter of the program can resolve leniently: answered by whether it
     * gives DateTimeFormatter.withResolverStyle, the one way to a lenient one, a style
     * that is no read of STRICT or SMART (its anyStyle), so that a program that cannot
     * has none of the lenient branches below.
     */
    static native boolean lenients();

    /** Where a field stands among the first of some fields, compared by equals, or -1. */
    static int among(TemporalField field, TemporalField[] fields, int count) {
        for (int i = 0; i < count; i++) {
            if (field == null ? fields[i] == null : field.equals(fields[i])) {
                return i;
            }
        }
        return -1;
    }

    /** Resolves the fields read: of the fields given only those, or all of them where none are. */
    void resolve(ResolverStyle style, ZoneId override, TemporalField[] resolverFields) {
        this.style = style;
        if (zone == null) {
            zone = override;
        }
        if (filtered()) {
            if (resolverFields != null) {
                for (int i = 0; i < count;) {
                    if (among(fields[i], resolverFields, resolverFields.length) >= 0) {
                        i++;
                    }
                    else {
                        remove(fields[i]);
                    }
                }
            }
        }
        if (Texts.ownCalendars()) {
            dates();
        }
        else {
            resolveDate();
        }
        if (others()) {
            resolveOthers();
        }
        resolveTimeFields();
        resolveTime();
        check();
        if (date != null && excessDays != 0) {
            date = date.plusDays(excessDays);
            excessDays = 0;
        }
        if (Texts.ownCalendars()) {
            if (calendarDate != null && excessDays != 0) {
                // a JDK adds them as an ISO period, which a date of another calendar refuses
                calendarDate = calendarDate.plus(Period.ofDays((int) excessDays));
                excessDays = 0;
            }
        }
        instant();
    }

    private boolean lenient() {
        return style == ResolverStyle.LENIENT;
    }

    private long checked(ChronoField field, long value) {
        return lenients() && lenient() ? value : field.checkValidValue(value);
    }

    private void resolveDate() {
        if (has(ChronoField.YEAR_OF_ERA)) {
            long yearOfEra = checked(ChronoField.YEAR_OF_ERA, valueOf(ChronoField.YEAR_OF_ERA));
            if (has(ChronoField.ERA)) {
                long era = remove(ChronoField.ERA);
                remove(ChronoField.YEAR_OF_ERA);
                if (era == 1) {
                    put(ChronoField.YEAR, yearOfEra, null);
                }
                else if (era == 0) {
                    put(ChronoField.YEAR, Math.subtractExact(1, yearOfEra), null);
                }
                else {
                    throw new DateTimeException("Invalid value for era: " + era);
                }
            }
            else if (has(ChronoField.YEAR)) {
                long year = valueOf(ChronoField.YEAR);
                remove(ChronoField.YEAR_OF_ERA);
                put(ChronoField.YEAR, year > 0 ? yearOfEra : Math.subtractExact(1, yearOfEra), null);
            }
            else if (style != ResolverStyle.STRICT) {
                remove(ChronoField.YEAR_OF_ERA);
                put(ChronoField.YEAR, yearOfEra, null);
            }
        }
        else if (has(ChronoField.ERA)) {
            ChronoField.ERA.checkValidValue(valueOf(ChronoField.ERA));
        }
        if (has(ChronoField.PROLEPTIC_MONTH)) {
            long month = checked(ChronoField.PROLEPTIC_MONTH, remove(ChronoField.PROLEPTIC_MONTH));
            put(ChronoField.MONTH_OF_YEAR, Math.floorMod(month, 12L) + 1, null);
            put(ChronoField.YEAR, Math.floorDiv(month, 12L), null);
        }
        if (has(ChronoField.EPOCH_DAY)) {
            // the other fields of a date are checked against this one
            date(LocalDate.ofEpochDay(remove(ChronoField.EPOCH_DAY)));
            return;
        }
        if (!has(ChronoField.YEAR)) {
            return;
        }
        if (has(ChronoField.MONTH_OF_YEAR)) {
            if (has(ChronoField.DAY_OF_MONTH)) {
                yearMonthDay();
                return;
            }
            if (has(ChronoField.ALIGNED_WEEK_OF_MONTH)) {
                if (has(ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH) || has(ChronoField.DAY_OF_WEEK)) {
                    weekOfMonth();
                }
                return;
            }
        }
        if (has(ChronoField.DAY_OF_YEAR)) {
            int year = ChronoField.YEAR.checkValidIntValue(remove(ChronoField.YEAR));
            long day = remove(ChronoField.DAY_OF_YEAR);
            date(lenients() && lenient() ? LocalDate.ofYearDay(year, 1).plusDays(Math.subtractExact(day, 1))
                    : LocalDate.ofYearDay(year, ChronoField.DAY_OF_YEAR.checkValidIntValue(day)));
            return;
        }
        if (has(ChronoField.ALIGNED_WEEK_OF_YEAR)
                && (has(ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR) || has(ChronoField.DAY_OF_WEEK))) {
            weekOfYear();
        }
    }

    /**
     * The fields that are no ChronoField resolve themselves (TemporalField.resolve), after
     * the date's fields: in the order read, the first that takes itself out or answers a
     * date or a time, then again from the first; what one puts among a date's fields is
     * resolved as they are. Fifty such changes are a field that goes on for ever, refused
     * as a JDK refuses it.
     */
    private void resolveOthers() {
        Fields view = new Fields();
        for (int changes = 0; resolveOne(view); changes++) {
            if (changes == 49) {
                throw new DateTimeException("One of the parsed fields has an incorrectly implemented resolve method");
            }
            if (view.dated) {
                view.dated = false;
                if (Texts.ownCalendars()) {
                    dates();
                }
                else {
                    resolveDate();
                }
            }
        }
    }

    private boolean resolveOne(Fields view) {
        for (int i = 0; i < count; i++) {
            TemporalField field = fields[i];
            if (field instanceof ChronoField) {
                continue;
            }
            TemporalAccessor made = field.resolve(view, this, style);
            if (made != null) {
                made(made);
                return true;
            }
            i = index(field);
            if (i < 0) {
                return true;
            }
        }
        return false;
    }

    /** A date, a time or both a field resolved to; of a zoned value its zone too. */
    private void made(TemporalAccessor made) {
        if (made instanceof ChronoZonedDateTime<?> zoned) {
            if (zone == null) {
                zone = zoned.getZone();
            }
            made = zoned.toLocalDateTime();
        }
        if (made instanceof ChronoLocalDateTime<?> local) {
            if (Texts.ownCalendars()) {
                dated(local.toLocalDate());
            }
            else {
                date(LocalDate.from(local.toLocalDate()));
            }
            time(local.toLocalTime());
        }
        else if (made instanceof ChronoLocalDate local) {
            if (Texts.ownCalendars()) {
                dated(local);
            }
            else {
                date(LocalDate.from(local));
            }
        }
        else if (made instanceof LocalTime local) {
            time(local);
        }
        else {
            throw new DateTimeException(
                    "Method resolve() can only return ChronoZonedDateTime, ChronoLocalDateTime, ChronoLocalDate or LocalTime");
        }
    }

    private void yearMonthDay() {
        int year = ChronoField.YEAR.checkValidIntValue(remove(ChronoField.YEAR));
        long month = remove(ChronoField.MONTH_OF_YEAR);
        long day = remove(ChronoField.DAY_OF_MONTH);
        if (lenients() && lenient()) {
            date(LocalDate.of(year, 1, 1).plusMonths(Math.subtractExact(month, 1)).plusDays(Math.subtractExact(day, 1)));
            return;
        }
        int m = ChronoField.MONTH_OF_YEAR.checkValidIntValue(month);
        int d = ChronoField.DAY_OF_MONTH.checkValidIntValue(day);
        if (style == ResolverStyle.SMART && d > 28) {
            // a day past the month's last is its last
            d = Math.min(d, LocalDate.of(year, m, 1).lengthOfMonth());
        }
        date(LocalDate.of(year, m, d));
    }

    /** The day of a week of the month: by the day in the week, or the day of the week after its start. */
    private void weekOfMonth() {
        int year = ChronoField.YEAR.checkValidIntValue(remove(ChronoField.YEAR));
        long month = remove(ChronoField.MONTH_OF_YEAR);
        long week = remove(ChronoField.ALIGNED_WEEK_OF_MONTH);
        boolean aligned = has(ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH);
        long day = remove(aligned ? ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH : ChronoField.DAY_OF_WEEK);
        if (lenients() && lenient()) {
            LocalDate start = LocalDate.of(year, 1, 1).plusMonths(Math.subtractExact(month, 1));
            date(weekDay(start.plusWeeks(Math.subtractExact(week, 1)), day, aligned, true));
            return;
        }
        int m = ChronoField.MONTH_OF_YEAR.checkValidIntValue(month);
        int w = ChronoField.ALIGNED_WEEK_OF_MONTH.checkValidIntValue(week);
        (aligned ? ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH : ChronoField.DAY_OF_WEEK).checkValidValue(day);
        LocalDate made = weekDay(LocalDate.of(year, m, 1).plusDays((w - 1) * 7L), day, aligned, false);
        if (style == ResolverStyle.STRICT && made.getMonthValue() != m) {
            throw new DateTimeException("Strict mode rejected resolved date as it is in a different month");
        }
        date(made);
    }

    private void weekOfYear() {
        int year = ChronoField.YEAR.checkValidIntValue(remove(ChronoField.YEAR));
        long week = remove(ChronoField.ALIGNED_WEEK_OF_YEAR);
        boolean aligned = has(ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR);
        long day = remove(aligned ? ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR : ChronoField.DAY_OF_WEEK);
        if (lenients() && lenient()) {
            date(weekDay(LocalDate.of(year, 1, 1).plusWeeks(Math.subtractExact(week, 1)), day, aligned, true));
            return;
        }
        int w = ChronoField.ALIGNED_WEEK_OF_YEAR.checkValidIntValue(week);
        (aligned ? ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR : ChronoField.DAY_OF_WEEK).checkValidValue(day);
        LocalDate made = weekDay(LocalDate.of(year, 1, 1).plusDays((w - 1) * 7L), day, aligned, false);
        if (style == ResolverStyle.STRICT && made.getYear() != year) {
            throw new DateTimeException("Strict mode rejected resolved date as it is in a different year");
        }
        date(made);
    }

    /**
     * The day of a week that starts at a date: the aligned day counted from it, or the day
     * of the week on or after it. Leniently that is the day before the one asked, a whole
     * week further for each seven past the last: a JDK 25's answers, the rule
     * AbstractChronology's map resolving follows too (one algorithm twice: this one over
     * the arrays, which a program reading a date pays 56 KB less of wasm for).
     */
    private static LocalDate weekDay(LocalDate start, long day, boolean aligned, boolean lenient) {
        if (aligned) {
            return start.plusDays(Math.subtractExact(day, 1));
        }
        LocalDate date = start;
        long asked = day;
        if (lenients()) {
            if (lenient) {
                asked = Math.subtractExact(day, 1);
                if (asked > 7) {
                    date = date.plusWeeks((asked - 1) / 7);
                    asked = (asked - 1) % 7 + 1;
                }
                else if (asked < 1) {
                    date = date.plusWeeks(Math.subtractExact(asked, 7) / 7);
                    asked = (asked + 6) % 7 + 1;
                }
                asked = java.time.DayOfWeek.of((int) asked).getValue();
            }
        }
        return date.plusDays(Math.floorMod(asked - date.getDayOfWeek().getValue(), 7L));
    }

    private void resolveTimeFields() {
        if (has(ChronoField.CLOCK_HOUR_OF_DAY)) {
            long hour = remove(ChronoField.CLOCK_HOUR_OF_DAY);
            if (style == ResolverStyle.STRICT || (style == ResolverStyle.SMART && hour != 0)) {
                ChronoField.CLOCK_HOUR_OF_DAY.checkValidValue(hour);
            }
            put(ChronoField.HOUR_OF_DAY, hour == 24 ? 0 : hour, ChronoField.CLOCK_HOUR_OF_DAY);
        }
        if (has(ChronoField.CLOCK_HOUR_OF_AMPM)) {
            long hour = remove(ChronoField.CLOCK_HOUR_OF_AMPM);
            if (style == ResolverStyle.STRICT || (style == ResolverStyle.SMART && hour != 0)) {
                ChronoField.CLOCK_HOUR_OF_AMPM.checkValidValue(hour);
            }
            put(ChronoField.HOUR_OF_AMPM, hour == 12 ? 0 : hour, ChronoField.CLOCK_HOUR_OF_AMPM);
        }
        if (periods()) {
            halfOfPeriod();
        }
        if (has(ChronoField.AMPM_OF_DAY) && has(ChronoField.HOUR_OF_AMPM)) {
            long half = remove(ChronoField.AMPM_OF_DAY);
            long hour = remove(ChronoField.HOUR_OF_AMPM);
            if (lenients() && lenient()) {
                put(ChronoField.HOUR_OF_DAY, Math.addExact(Math.multiplyExact(half, 12), hour), ChronoField.AMPM_OF_DAY);
            }
            else {
                ChronoField.AMPM_OF_DAY.checkValidValue(half);
                ChronoField.HOUR_OF_AMPM.checkValidValue(hour);
                put(ChronoField.HOUR_OF_DAY, half * 12 + hour, ChronoField.AMPM_OF_DAY);
            }
        }
        if (has(ChronoField.NANO_OF_DAY)) {
            long nanos = checked(ChronoField.NANO_OF_DAY, remove(ChronoField.NANO_OF_DAY));
            put(ChronoField.HOUR_OF_DAY, nanos / 3600000000000L, ChronoField.NANO_OF_DAY);
            put(ChronoField.MINUTE_OF_HOUR, nanos / 60000000000L % 60, ChronoField.NANO_OF_DAY);
            put(ChronoField.SECOND_OF_MINUTE, nanos / NANOS_PER_SECOND % 60, ChronoField.NANO_OF_DAY);
            put(ChronoField.NANO_OF_SECOND, nanos % NANOS_PER_SECOND, ChronoField.NANO_OF_DAY);
        }
        if (has(ChronoField.MICRO_OF_DAY)) {
            long micros = checked(ChronoField.MICRO_OF_DAY, remove(ChronoField.MICRO_OF_DAY));
            put(ChronoField.SECOND_OF_DAY, micros / 1000000L, ChronoField.MICRO_OF_DAY);
            put(ChronoField.MICRO_OF_SECOND, micros % 1000000L, ChronoField.MICRO_OF_DAY);
        }
        if (has(ChronoField.MILLI_OF_DAY)) {
            long millis = checked(ChronoField.MILLI_OF_DAY, remove(ChronoField.MILLI_OF_DAY));
            put(ChronoField.SECOND_OF_DAY, millis / 1000, ChronoField.MILLI_OF_DAY);
            put(ChronoField.MILLI_OF_SECOND, millis % 1000, ChronoField.MILLI_OF_DAY);
        }
        if (has(ChronoField.SECOND_OF_DAY)) {
            long seconds = checked(ChronoField.SECOND_OF_DAY, remove(ChronoField.SECOND_OF_DAY));
            put(ChronoField.HOUR_OF_DAY, seconds / 3600, ChronoField.SECOND_OF_DAY);
            put(ChronoField.MINUTE_OF_HOUR, seconds / 60 % 60, ChronoField.SECOND_OF_DAY);
            put(ChronoField.SECOND_OF_MINUTE, seconds % 60, ChronoField.SECOND_OF_DAY);
        }
        if (has(ChronoField.MINUTE_OF_DAY)) {
            long minutes = checked(ChronoField.MINUTE_OF_DAY, remove(ChronoField.MINUTE_OF_DAY));
            put(ChronoField.HOUR_OF_DAY, minutes / 60, ChronoField.MINUTE_OF_DAY);
            put(ChronoField.MINUTE_OF_HOUR, minutes % 60, ChronoField.MINUTE_OF_DAY);
        }
        // a fraction of a second in thousandths or millionths joins the finer one; it is
        // checked where that one was read too
        if (has(ChronoField.MILLI_OF_SECOND)) {
            long millis = remove(ChronoField.MILLI_OF_SECOND);
            if (has(ChronoField.MICRO_OF_SECOND)) {
                put(ChronoField.MICRO_OF_SECOND, millis * 1000 + valueOf(ChronoField.MICRO_OF_SECOND) % 1000,
                        ChronoField.MILLI_OF_SECOND);
            }
            else if (has(ChronoField.NANO_OF_SECOND)) {
                checked(ChronoField.MILLI_OF_SECOND, millis);
                put(ChronoField.NANO_OF_SECOND, millis * 1000000 + valueOf(ChronoField.NANO_OF_SECOND) % 1000000,
                        ChronoField.MILLI_OF_SECOND);
            }
            else {
                put(ChronoField.NANO_OF_SECOND, millis * 1000000, ChronoField.MILLI_OF_SECOND);
            }
        }
        if (has(ChronoField.MICRO_OF_SECOND)) {
            long micros = remove(ChronoField.MICRO_OF_SECOND);
            if (has(ChronoField.NANO_OF_SECOND)) {
                checked(ChronoField.MICRO_OF_SECOND, micros);
                put(ChronoField.NANO_OF_SECOND, micros * 1000 + valueOf(ChronoField.NANO_OF_SECOND) % 1000,
                        ChronoField.MICRO_OF_SECOND);
            }
            else {
                put(ChronoField.NANO_OF_SECOND, micros * 1000, ChronoField.MICRO_OF_SECOND);
            }
        }
        if (periods()) {
            middleOfPeriod();
        }
        if (time == null && style != ResolverStyle.STRICT && has(ChronoField.AMPM_OF_DAY) && !has(ChronoField.HOUR_OF_DAY)
                && !has(ChronoField.HOUR_OF_AMPM) && !has(ChronoField.MINUTE_OF_HOUR)
                && !has(ChronoField.SECOND_OF_MINUTE) && !has(ChronoField.NANO_OF_SECOND)) {
            // half a day alone is its middle
            long half = remove(ChronoField.AMPM_OF_DAY);
            if (!(lenients() && lenient())) {
                ChronoField.AMPM_OF_DAY.checkValidValue(half);
            }
            put(ChronoField.HOUR_OF_DAY, Math.addExact(Math.multiplyExact(half, 12), 6), ChronoField.AMPM_OF_DAY);
        }
        else if (!(lenients() && lenient()) && has(ChronoField.AMPM_OF_DAY)) {
            ChronoField.AMPM_OF_DAY.checkValidValue(valueOf(ChronoField.AMPM_OF_DAY));
        }
        if (!(lenients() && lenient()) && has(ChronoField.HOUR_OF_AMPM)) {
            ChronoField.HOUR_OF_AMPM.checkValidValue(valueOf(ChronoField.HOUR_OF_AMPM));
        }
    }

    /** A time of the hour, the minute, the second and the nanosecond, each where the one before is. */
    /**
     * A period of the day read with the hour of a half of the day and no half: the half
     * in which the hour lies in the period; the first where it lies in neither.
     */
    private void halfOfPeriod() {
        if (period != null && has(ChronoField.HOUR_OF_AMPM) && !has(ChronoField.AMPM_OF_DAY)) {
            long minute = Math.floorMod(valueOf(ChronoField.HOUR_OF_AMPM), 12L) * 60 + 720
                    + (has(ChronoField.MINUTE_OF_HOUR) ? valueOf(ChronoField.MINUTE_OF_HOUR) : 0);
            append(ChronoField.AMPM_OF_DAY, Step.DayPeriod.contains(period[0], period[1], minute) ? 1 : 0);
            period = null;
        }
    }

    /** A period of the day read with no field of a time: its middle, but strictly no time. */
    private void middleOfPeriod() {
        if (period != null && time == null && !has(ChronoField.HOUR_OF_DAY) && !has(ChronoField.HOUR_OF_AMPM)
                && !has(ChronoField.MINUTE_OF_HOUR) && !has(ChronoField.SECOND_OF_MINUTE)
                && !has(ChronoField.NANO_OF_SECOND)) {
            if (style != ResolverStyle.STRICT) {
                int middle = (period[0] + period[1]) / 2;
                put(ChronoField.HOUR_OF_DAY, middle / 60, null);
                put(ChronoField.MINUTE_OF_HOUR, middle % 60, null);
            }
            period = null;
        }
    }

    /**
     * An hour read with the period of the day it must lie in, checked before the hour
     * itself; leniently not.
     */
    private void hourInPeriod() {
        if (period != null && has(ChronoField.HOUR_OF_DAY)) {
            if (!(lenients() && lenient())) {
                long hour = valueOf(ChronoField.HOUR_OF_DAY);
                long minute = has(ChronoField.MINUTE_OF_HOUR) ? valueOf(ChronoField.MINUTE_OF_HOUR) : 0;
                if (!Step.DayPeriod.contains(period[0], period[1], hour * 60 + minute)) {
                    throw new DateTimeException("Conflict found: Resolved time " + (hour < 10 ? "0" : "") + hour
                            + (minute < 10 ? ":0" : ":") + minute + " conflicts with "
                            + Step.DayPeriod.shown(period[0], period[1]));
                }
            }
            period = null;
        }
    }

    private void resolveTime() {
        if (periods()) {
            hourInPeriod();
        }
        if (time == null && has(ChronoField.HOUR_OF_DAY)) {
            boolean minute = has(ChronoField.MINUTE_OF_HOUR);
            boolean second = has(ChronoField.SECOND_OF_MINUTE);
            boolean nano = has(ChronoField.NANO_OF_SECOND);
            if ((minute || (!second && !nano)) && (second || !nano)) {
                long h = remove(ChronoField.HOUR_OF_DAY);
                long m = minute ? remove(ChronoField.MINUTE_OF_HOUR) : 0;
                long s = second ? remove(ChronoField.SECOND_OF_MINUTE) : 0;
                long n = nano ? remove(ChronoField.NANO_OF_SECOND) : 0;
                if (lenients() && lenient()) {
                    long total = Math.addExact(Math.addExact(Math.addExact(Math.multiplyExact(h, 3600000000000L),
                            Math.multiplyExact(m, 60000000000L)), Math.multiplyExact(s, NANOS_PER_SECOND)), n);
                    excessDays = Math.floorDiv(total, NANOS_PER_DAY);
                    time = LocalTime.ofNanoOfDay(Math.floorMod(total, NANOS_PER_DAY));
                    return;
                }
                int checkedMinute = ChronoField.MINUTE_OF_HOUR.checkValidIntValue(m);
                if (style == ResolverStyle.SMART && h == 24 && checkedMinute == 0 && s == 0 && n == 0) {
                    // the end of a day is the next one's start
                    time = LocalTime.MIDNIGHT;
                    excessDays = 1;
                    return;
                }
                int checkedHour = ChronoField.HOUR_OF_DAY.checkValidIntValue(h);
                time = LocalTime.of(checkedHour, checkedMinute, ChronoField.SECOND_OF_MINUTE.checkValidIntValue(s),
                        ChronoField.NANO_OF_SECOND.checkValidIntValue(n));
                return;
            }
        }
        if (!(lenients() && lenient()) && !has(ChronoField.HOUR_OF_DAY)) {
            // with no hour each field is checked by itself, the finest first
            for (ChronoField field : new ChronoField[] {ChronoField.NANO_OF_SECOND, ChronoField.SECOND_OF_MINUTE,
                    ChronoField.MINUTE_OF_HOUR}) {
                if (has(field)) {
                    field.checkValidValue(get(field));
                }
            }
        }
        if (time == null && (has(ChronoField.SECOND_OF_MINUTE) || has(ChronoField.INSTANT_SECONDS))) {
            // a second with no time: its fraction in each of the three fields
            long nano = has(ChronoField.NANO_OF_SECOND) ? valueOf(ChronoField.NANO_OF_SECOND) : 0;
            put(ChronoField.NANO_OF_SECOND, nano, null);
            put(ChronoField.MICRO_OF_SECOND, nano / 1000, null);
            put(ChronoField.MILLI_OF_SECOND, nano / 1000000, null);
        }
    }

    /** Every field left that the date or the time has is checked against it. */
    private void check() {
        for (int i = 0; i < count;) {
            TemporalField field = fields[i];
            TemporalAccessor made = null;
            if (field instanceof ChronoField) {
                made = field.isDateBased() ? date : field.isTimeBased() ? time : null;
                if (Texts.ownCalendars()) {
                    if (calendarDate != null && field.isDateBased()) {
                        made = calendarDate;
                    }
                }
            }
            else if (others()) {
                made = date != null && date.isSupported(field) ? date : time;
                if (Texts.ownCalendars()) {
                    if (calendarDate != null && calendarDate.isSupported(field)) {
                        made = calendarDate;
                    }
                }
            }
            if (made != null && made.isSupported(field)) {
                long value = made.getLong(field);
                if (value != values[i]) {
                    throw new DateTimeException("Conflict found: Field " + field.toString() + " " + value + " differs from "
                            + field.toString() + " " + values[i] + " derived from " + made.toString());
                }
                remove(field);
            }
            else {
                i++;
            }
        }
    }

    /**
     * The instant a date and a time are at their offset or zone; or, where an instant was
     * read and a zone or an offset is known, the date and the time it is there.
     */
    private void instant() {
        if (has(ChronoField.INSTANT_SECONDS) && (zone != null || has(ChronoField.OFFSET_SECONDS))) {
            long second = valueOf(ChronoField.INSTANT_SECONDS);
            int nano = has(ChronoField.NANO_OF_SECOND) ? (int) remove(ChronoField.NANO_OF_SECOND) : 0;
            if (has(ChronoField.MICRO_OF_SECOND)) {
                remove(ChronoField.MICRO_OF_SECOND);
            }
            if (has(ChronoField.MILLI_OF_SECOND)) {
                remove(ChronoField.MILLI_OF_SECOND);
            }
            if (Texts.ownCalendars()) {
                if (otherCalendar()) {
                    // the calendar's zoned date-time of the instant
                    ChronoZonedDateTime<?> zoned = chronology.zonedDateTime(java.time.Instant.ofEpochSecond(second, nano),
                            zone != null ? zone
                                    : ZoneOffset.ofTotalSeconds(Math.toIntExact(valueOf(ChronoField.OFFSET_SECONDS))));
                    calendarDate = zoned.toLocalDate();
                    time = zoned.toLocalTime();
                    put(ChronoField.OFFSET_SECONDS, zoned.getOffset().getTotalSeconds(), null);
                    return;
                }
            }
            java.time.OffsetDateTime local = zone != null
                    ? java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(second, nano), zone)
                        .toOffsetDateTime()
                    : java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(second, nano),
                            ZoneOffset.ofTotalSeconds(Math.toIntExact(valueOf(ChronoField.OFFSET_SECONDS))));
            date = local.toLocalDate();
            time = local.toLocalTime();
            put(ChronoField.OFFSET_SECONDS, local.getOffset().getTotalSeconds(), null);
            return;
        }
        LocalDate day = date;
        if (Texts.ownCalendars()) {
            if (calendarDate != null) {
                // the calendar's date-time is at the instant ISO's of the same day and time is
                day = LocalDate.ofEpochDay(calendarDate.toEpochDay());
            }
        }
        if (day == null || time == null) {
            return;
        }
        if (has(ChronoField.OFFSET_SECONDS)) {
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(Math.toIntExact(valueOf(ChronoField.OFFSET_SECONDS)));
            put(ChronoField.INSTANT_SECONDS, day.atTime(time).toEpochSecond(offset), null);
        }
        else if (zone != null) {
            java.time.ZonedDateTime zoned = day.atTime(time).atZone(zone);
            if (zoneTexts()) {
                // in an overlap a name of standard time or of the zone as a whole is the
                // later offset, any other the earlier
                if (zoneTime == 0 || zoneTime == 2) {
                    zoned = zoned.withLaterOffsetAtOverlap();
                }
            }
            put(ChronoField.INSTANT_SECONDS, zoned.toEpochSecond(), null);
        }
    }

    public boolean isSupported(TemporalField field) {
        if (Texts.ownCalendars()) {
            if (calendarDate != null && calendarDate.isSupported(field)) {
                return true;
            }
        }
        if (has(field) || (date != null && date.isSupported(field)) || (time != null && time.isSupported(field))) {
            return true;
        }
        return field != null && !(field instanceof ChronoField) && field.isSupportedBy(this);
    }

    public long getLong(TemporalField field) {
        java.util.Objects.requireNonNull(field, "field");
        int index = index(field);
        if (index >= 0) {
            return values[index];
        }
        if (Texts.ownCalendars()) {
            if (calendarDate != null && calendarDate.isSupported(field)) {
                return calendarDate.getLong(field);
            }
        }
        if (date != null && date.isSupported(field)) {
            return date.getLong(field);
        }
        if (time != null && time.isSupported(field)) {
            return time.getLong(field);
        }
        if (field instanceof ChronoField) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field.toString());
        }
        return field.getFrom(this);
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        if (query == TemporalQueries.zoneId()) {
            return (R) zone;
        }
        if (query == TemporalQueries.localDate()) {
            if (Texts.ownCalendars()) {
                if (calendarDate != null) {
                    return (R) LocalDate.ofEpochDay(calendarDate.toEpochDay());
                }
            }
            return (R) date;
        }
        if (query == TemporalQueries.localTime()) {
            return (R) time;
        }
        if (query == TemporalQueries.offset() || query == TemporalQueries.zone()) {
            ZoneId offset = has(ChronoField.OFFSET_SECONDS)
                    ? ZoneOffset.ofTotalSeconds(Math.toIntExact(valueOf(ChronoField.OFFSET_SECONDS)))
                    : zone instanceof ZoneOffset ? zone : null;
            return (R) (query == TemporalQueries.zone() && zone != null ? zone : offset);
        }
        if (query == TemporalQueries.precision()) {
            return null;
        }
        if (calendars()) {
            if (query == TemporalQueries.chronology()) {
                if (Texts.ownCalendars()) {
                    if (style != null && chronology != null) {
                        return (R) chronology;
                    }
                }
                // none before the fields are resolved
                return (R) (style == null ? null : IsoChronology.INSTANCE);
            }
        }
        return query.queryFrom(this);
    }

    /** The fields read as the map a field's resolve takes: what it changes, the arrays hold. */
    private final class Fields extends AbstractMap<TemporalField, Long> {

        /** Whether a field of a date was put since the last look. */
        boolean dated;

        public boolean containsKey(Object key) {
            return key instanceof TemporalField field && has(field);
        }

        public Long get(Object key) {
            return containsKey(key) ? valueOf((TemporalField) key) : null;
        }

        public Long put(TemporalField key, Long value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (key instanceof ChronoField && key.isDateBased()) {
                dated = true;
            }
            int index = index(key);
            if (index >= 0) {
                long old = values[index];
                values[index] = value;
                return old;
            }
            append(key, value);
            return null;
        }

        public Long remove(Object key) {
            return containsKey(key) ? Parsed.this.remove((TemporalField) key) : null;
        }

        public Set<Map.Entry<TemporalField, Long>> entrySet() {
            List<Map.Entry<TemporalField, Long>> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                entries.add(new SimpleImmutableEntry<>(fields[i], values[i]));
            }
            return new AbstractSet<>() {
                public Iterator<Map.Entry<TemporalField, Long>> iterator() {
                    return entries.iterator();
                }

                public int size() {
                    return entries.size();
                }
            };
        }
    }

    public String toString() {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(fields[i].toString()).append('=').append(values[i]);
        }
        // the calendar, which fields not yet resolved have none of
        String calendar = style == null ? "},null" : "},ISO";
        String day = date == null ? null : date.toString();
        if (Texts.ownCalendars()) {
            if (style != null && chronology != null) {
                calendar = "}," + chronology.toString();
            }
            if (calendarDate != null) {
                day = calendarDate.toString();
            }
        }
        text.append(calendar);
        if (zone != null) {
            text.append(',').append(zone.getId()).append(',').append(zoneTime);
        }
        if (day != null || time != null) {
            text.append(" resolved to ");
            if (day != null) {
                text.append(day);
                if (time != null) {
                    text.append('T');
                }
            }
            if (time != null) {
                text.append(time.toString());
            }
        }
        return text.toString();
    }
}
