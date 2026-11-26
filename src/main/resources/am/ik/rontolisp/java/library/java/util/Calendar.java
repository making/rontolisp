// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The fields of a calendar and the instant they name. Setting the instant computes its
// fields at once; a set stores a field and computes nothing; a computation finds the
// instant of the fields set since the last one, then every field of the instant
// (computeTime and computeFields, GregorianCalendar's), after which every field counts as
// set, earlier than any later set. Which fields decide is the order they were set in,
// kept here: 0 never set, 1 computed, then 2, 3 .. by the program since. When a
// computation happens, what the fields of an instant are, and what a zone and the week
// pair change are a JDK's answers.
package java.util;

import java.io.Serializable;

public abstract class Calendar implements Serializable, Cloneable, Comparable<Calendar> {

    public static final int ERA = 0;

    public static final int YEAR = 1;

    public static final int MONTH = 2;

    public static final int WEEK_OF_YEAR = 3;

    public static final int WEEK_OF_MONTH = 4;

    public static final int DATE = 5;

    public static final int DAY_OF_MONTH = 5;

    public static final int DAY_OF_YEAR = 6;

    public static final int DAY_OF_WEEK = 7;

    public static final int DAY_OF_WEEK_IN_MONTH = 8;

    public static final int AM_PM = 9;

    public static final int HOUR = 10;

    public static final int HOUR_OF_DAY = 11;

    public static final int MINUTE = 12;

    public static final int SECOND = 13;

    public static final int MILLISECOND = 14;

    public static final int ZONE_OFFSET = 15;

    public static final int DST_OFFSET = 16;

    public static final int FIELD_COUNT = 17;

    public static final int SUNDAY = 1;

    public static final int MONDAY = 2;

    public static final int TUESDAY = 3;

    public static final int WEDNESDAY = 4;

    public static final int THURSDAY = 5;

    public static final int FRIDAY = 6;

    public static final int SATURDAY = 7;

    public static final int JANUARY = 0;

    public static final int FEBRUARY = 1;

    public static final int MARCH = 2;

    public static final int APRIL = 3;

    public static final int MAY = 4;

    public static final int JUNE = 5;

    public static final int JULY = 6;

    public static final int AUGUST = 7;

    public static final int SEPTEMBER = 8;

    public static final int OCTOBER = 9;

    public static final int NOVEMBER = 10;

    public static final int DECEMBER = 11;

    public static final int UNDECIMBER = 12;

    public static final int AM = 0;

    public static final int PM = 1;

    public static final int ALL_STYLES = 0;

    public static final int SHORT = 1;

    public static final int LONG = 2;

    public static final int NARROW_FORMAT = 4;

    public static final int NARROW_STANDALONE = 32772;

    public static final int SHORT_FORMAT = 1;

    public static final int LONG_FORMAT = 2;

    public static final int SHORT_STANDALONE = 32769;

    public static final int LONG_STANDALONE = 32770;

    protected int[] fields;

    protected boolean[] isSet;

    protected long time;

    protected boolean isTimeSet;

    protected boolean areFieldsSet;

    /** When each field was set: 0 never, 1 by a computation, 2 on in the order the program set them. */
    int[] order;

    /** The order the next set gives. */
    int nextOrder;

    /**
     * The offset fields the program set that the last computation kept (1 ZONE_OFFSET, 2
     * DST_OFFSET): the fields of the instant keep them until the zone or the instant is
     * changed.
     */
    int kept;

    /**
     * Why the fields of the instant set last were refused, while no fields stand in their
     * place (GregorianCalendar's): a set that would join them is refused then; a
     * computation asks again.
     */
    String refused;

    private TimeZone zone;

    private int firstDayOfWeek;

    private int minimalDaysInFirstWeek;

    protected Calendar() {
        this(TimeZone.getDefault());
    }

    /**
     * A calendar in a zone, of the default locale's week pair: the default locale is the
     * United States' (Locale.getDefault()), whose weeks start on Sunday and whose year's
     * first week is the one holding its first day.
     */
    Calendar(TimeZone zone) {
        fields = new int[FIELD_COUNT];
        isSet = new boolean[FIELD_COUNT];
        order = new int[FIELD_COUNT];
        nextOrder = 2;
        this.zone = zone;
        firstDayOfWeek = SUNDAY;
        minimalDaysInFirstWeek = 1;
    }

    public static Calendar getInstance() {
        return new GregorianCalendar();
    }

    public final Date getTime() {
        return new Date(getTimeInMillis());
    }

    public final void setTime(Date date) {
        if (date == null) {
            throw new NullPointerException("date must not be null");
        }
        setTimeInMillis(date.getTime());
    }

    public long getTimeInMillis() {
        if (!isTimeSet) {
            complete();
        }
        return time;
    }

    public void setTimeInMillis(long millis) {
        time = millis;
        isTimeSet = true;
        areFieldsSet = false;
        kept = 0;
        for (int field = 0; field < FIELD_COUNT; field++) {
            isSet[field] = false;
            order[field] = 0;
        }
        nextOrder = 2;
        // the fields of the instant, at once: a set joins them whatever zone is held then
        instantSet();
    }

    /** Computes the fields of an instant just set. */
    void instantSet() {
        computeFields();
        areFieldsSet = true;
    }

    public int get(int field) {
        complete();
        return fields[field];
    }

    public void set(int field, int value) {
        if (refused != null) {
            throw new UnsupportedOperationException(refused);
        }
        fields[field] = value;
        isSet[field] = true;
        order[field] = nextOrder++;
        isTimeSet = false;
        areFieldsSet = false;
    }

    public final void set(int year, int month, int date) {
        set(YEAR, year);
        set(MONTH, month);
        set(DAY_OF_MONTH, date);
    }

    public final void set(int year, int month, int date, int hourOfDay, int minute) {
        set(year, month, date);
        set(HOUR_OF_DAY, hourOfDay);
        set(MINUTE, minute);
    }

    public final void set(int year, int month, int date, int hourOfDay, int minute, int second) {
        set(year, month, date, hourOfDay, minute);
        set(SECOND, second);
    }

    public final void clear() {
        for (int field = 0; field < FIELD_COUNT; field++) {
            fields[field] = 0;
            isSet[field] = false;
            order[field] = 0;
        }
        nextOrder = 2;
        kept = 0;
        refused = null;
        isTimeSet = false;
        areFieldsSet = false;
    }

    protected void complete() {
        if (!isTimeSet) {
            computeTime();
            isTimeSet = true;
        }
        if (!areFieldsSet) {
            computeFields();
            areFieldsSet = true;
        }
    }

    protected abstract void computeTime();

    protected abstract void computeFields();

    public TimeZone getTimeZone() {
        return zone;
    }

    public void setTimeZone(TimeZone value) {
        zone = value;
        if (isTimeSet) {
            // nothing set since the last computation: the instant stays and the next
            // computation takes its fields in the zone; a set before it joins the fields as
            // they stand
            areFieldsSet = false;
            kept = 0;
        }
    }

    public boolean isLenient() {
        return true;
    }

    public int getFirstDayOfWeek() {
        return firstDayOfWeek;
    }

    public void setFirstDayOfWeek(int value) {
        int old = firstDayOfWeek;
        firstDayOfWeek = week(value, "first day of the week");
        if (value != old) {
            paired();
        }
    }

    public int getMinimalDaysInFirstWeek() {
        return minimalDaysInFirstWeek;
    }

    public void setMinimalDaysInFirstWeek(int value) {
        int old = minimalDaysInFirstWeek;
        minimalDaysInFirstWeek = week(value, "least count of days of a first week");
        if (value != old) {
            paired();
        }
    }

    /** A value of the week pair, checked. */
    private static int week(int value, String what) {
        if (value < 1 || value > 7) {
            throw new UnsupportedOperationException(
                    "a " + what + " outside 1 to 7 is not supported by the experimental Java subset yet");
        }
        return value;
    }

    /**
     * What a new week pair does to the fields: nothing here (a calendar of the program's own
     * is asked nothing); GregorianCalendar computes them again.
     */
    void paired() {
    }

    public abstract void add(int field, int amount);

    public abstract void roll(int field, boolean up);

    public void roll(int field, int amount) {
        // rolls of one unit, as many as the amount says
        for (; amount > 0; amount--) {
            roll(field, true);
        }
        for (; amount < 0; amount++) {
            roll(field, false);
        }
    }

    public abstract int getMinimum(int field);

    public abstract int getMaximum(int field);

    public abstract int getGreatestMinimum(int field);

    public abstract int getLeastMaximum(int field);

    public int compareTo(Calendar anotherCalendar) {
        long one = getTimeInMillis();
        long other = anotherCalendar.getTimeInMillis();
        return one < other ? -1 : one == other ? 0 : 1;
    }

    public boolean before(Object when) {
        return when instanceof Calendar that && compareTo(that) < 0;
    }

    public boolean after(Object when) {
        return when instanceof Calendar that && compareTo(that) > 0;
    }

    public boolean equals(Object obj) {
        return obj == this || obj instanceof Calendar that && same(that);
    }

    /** The same instant, week pair and an equal zone (both lenient). */
    final boolean same(Calendar that) {
        return getTimeInMillis() == that.getTimeInMillis() && firstDayOfWeek == that.firstDayOfWeek
                && minimalDaysInFirstWeek == that.minimalDaysInFirstWeek && zone.equals(that.zone);
    }

    public int hashCode() {
        // any number equal calendars share: Objects.hash of what equals compares
        return Objects.hash(getTimeInMillis(), firstDayOfWeek, minimalDaysInFirstWeek, zone.hashCode());
    }

    public Object clone() {
        Calendar copy = (Calendar) rontolisp.internal.Support.copy(this);
        copy.fields = fields.clone();
        copy.isSet = isSet.clone();
        copy.order = order.clone();
        copy.zone = (TimeZone) zone.clone();
        return copy;
    }

}
