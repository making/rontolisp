package shim;

// Stands where java.text.Normalizer is named, for the measurement only: it normalizes
// nothing.
public final class Normalizer {

    public enum Form {
        NFD, NFC, NFKD, NFKC
    }

    public static String normalize(CharSequence text, Form form) {
        return text.toString();
    }
}
