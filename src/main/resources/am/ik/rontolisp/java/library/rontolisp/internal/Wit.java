// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The values of a WIT boundary as the Java a @WitImport interface's binding is written in
// reads and makes them (the binding is generated: java/JavaWitImports). They are the
// settled Common Lisp tier (.kb/wit.md): a list a list, a record a keyword plist, an
// enum's or a payload-less case's value its keyword, a case with a payload the cons of
// its keyword and the payload, flags a list of keywords, an option the value or nil, a
// result's envelope (:ok . value) or (:error . value). A label is the WIT's, written as a
// constant: the keyword it names is made where the program is compiled.
package rontolisp.internal;

public final class Wit {

    private Wit() {
    }

    public static native Object first(Object list);

    public static native Object rest(Object list);

    public static native Object cons(Object first, Object rest);

    public static native Object reversed(Object list);

    public static native Object label(String label);

    public static native boolean is(Object value, String label);

    public static native Object field(Object record, String label);

    // a case's keyword: the value itself, or the head of a case with a payload
    public static native Object tag(Object value);

    // a case's payload, a result's arm: what the cons holds after its keyword
    public static native Object payload(Object value);

    public static native boolean failed(Object envelope);

    public static native long inLong(Object value);

    public static native Object outSigned(long value);

    public static native Object outUnsigned(long value, int bits);

    public static native double inDouble(Object value);

    public static native Object outDouble(double value);

    public static native boolean inBoolean(Object value);

    public static native Object outBoolean(boolean value);

    public static native int inCodePoint(Object value);

    public static native Object outCodePoint(int codePoint);

    public static native String inString(Object value);

    public static native Object outString(String value);

    public static native byte[] inBytes(Object value);

    public static native Object outBytes(byte[] value);

    public static native int inHandle(Object value);

    public static native Object outHandle(int handle);
}
