// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What writes a value as text and reads one back from a position. Reading the whole of
// a text refuses one whose start is not read, at the index the reading failed, in the
// words a JDK 25 uses.
package java.text;

public abstract class Format implements Cloneable {

    protected Format() {
    }

    public Object clone() {
        return rontolisp.internal.Support.copy(this);
    }

    public final String format(Object obj) {
        return format(obj, new StringBuffer(), new FieldPosition(0)).toString();
    }

    public abstract StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos);

    public abstract Object parseObject(String source, ParsePosition pos);

    public Object parseObject(String source) throws ParseException {
        ParsePosition pos = new ParsePosition(0);
        Object result = parseObject(source, pos);
        if (pos.getIndex() == 0) {
            throw new ParseException("Format.parseObject(String) failed", pos.getErrorIndex());
        }
        return result;
    }

    /** A name of a field of a formatted text. */
    public static class Field {

        private final String name;

        protected Field(String name) {
            this.name = name;
        }

        protected String getName() {
            return name;
        }

        public String toString() {
            return getClass().getName() + "(" + name + ")";
        }
    }
}
