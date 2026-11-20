// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date or a time being written by a formatter. Inside an optional part a field the
// value lacks makes the part write nothing; anywhere else it is the value's refusal.
package java.time.format;

import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;

final class Writing {

    final TemporalAccessor temporal;

    final int texts;

    final StringBuilder text = new StringBuilder(32);

    int optional;

    Writing(TemporalAccessor temporal, int texts) {
        this.temporal = temporal;
        this.texts = texts;
    }

    /** Whether a field can be written: false only inside an optional part. */
    boolean has(TemporalField field) {
        return optional == 0 || temporal.isSupported(field);
    }

    long value(TemporalField field) {
        return temporal.getLong(field);
    }
}
