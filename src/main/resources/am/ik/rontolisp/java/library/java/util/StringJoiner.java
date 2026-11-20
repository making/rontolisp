// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The elements joined so far are one text without the prefix and the suffix, which are put
// around it when the whole is asked for. Which null a constructor refuses first and the
// words of each refusal were read off a JDK's answers.
public final class StringJoiner {

    private final String prefix;

    private final String delimiter;

    private final String suffix;

    private final StringBuilder joined = new StringBuilder();

    // whether an element was added or merged, the empty one included
    private boolean given;

    // what an empty joiner is, or null for the prefix and the suffix
    private String emptyValue;

    public StringJoiner(CharSequence delimiter) {
        this(delimiter, "", "");
    }

    public StringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        Objects.requireNonNull(prefix, "The prefix must not be null");
        Objects.requireNonNull(delimiter, "The delimiter must not be null");
        Objects.requireNonNull(suffix, "The suffix must not be null");
        this.prefix = prefix.toString();
        this.delimiter = delimiter.toString();
        this.suffix = suffix.toString();
    }

    public StringJoiner setEmptyValue(CharSequence emptyValue) {
        this.emptyValue = Objects.requireNonNull(emptyValue, "The empty value must not be null").toString();
        return this;
    }

    public StringJoiner add(CharSequence newElement) {
        String element = String.valueOf(newElement);
        if (given) {
            joined.append(delimiter);
        }
        given = true;
        joined.append(element);
        return this;
    }

    // The other's elements as one, without its prefix and suffix; read before anything is
    // appended, so that a joiner merged with itself takes what it held.
    public StringJoiner merge(StringJoiner other) {
        Objects.requireNonNull(other);
        if (other.given) {
            String elements = other.joined.toString();
            if (given) {
                joined.append(delimiter);
            }
            given = true;
            joined.append(elements);
        }
        return this;
    }

    public int length() {
        if (!given && emptyValue != null) {
            return emptyValue.length();
        }
        return prefix.length() + joined.length() + suffix.length();
    }

    public String toString() {
        if (!given && emptyValue != null) {
            return emptyValue;
        }
        return prefix + joined.toString() + suffix;
    }
}
