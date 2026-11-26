// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The four normalization forms of Unicode Standard Annex #15, over the forms the front
// ends share (eval/unicode-normalization.lisp; .kb/unicode-normalization.md): a string's
// elements are code points, so a form is a function of the string.
package java.text;

public final class Normalizer {

    public enum Form {

        NFD, NFC, NFKD, NFKC

    }

    private Normalizer() {
    }

    public static String normalize(CharSequence src, Form form) {
        String text = src.toString();
        int ordinal = form.ordinal();
        return normalize0(text, ordinal >= 2, (ordinal & 1) != 0);
    }

    public static boolean isNormalized(CharSequence src, Form form) {
        String text = src.toString();
        return normalize(text, form).equals(text);
    }

    /** The text in a form: of the compatibility decomposition, composed again. */
    private static native String normalize0(String text, boolean compatibility, boolean composed);

}
