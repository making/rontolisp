// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.regex;

// The message is the engine's text of a refusal: the description, the index, the
// pattern and a caret under the index.
public class PatternSyntaxException extends IllegalArgumentException {

    private final String description;

    private final String pattern;

    private final int index;

    public PatternSyntaxException(String description, String regex, int index) {
        super(Pattern.message0(description, index, regex));
        this.description = description;
        this.pattern = regex;
        this.index = index;
    }

    public String getDescription() {
        return description;
    }

    public String getPattern() {
        return pattern;
    }

    public int getIndex() {
        return index;
    }
}
