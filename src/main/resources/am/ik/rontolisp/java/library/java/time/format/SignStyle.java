// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.format;

// When a number is written and read with its sign.
public enum SignStyle {

    NORMAL, ALWAYS, NEVER, NOT_NEGATIVE, EXCEEDS_PAD
}
