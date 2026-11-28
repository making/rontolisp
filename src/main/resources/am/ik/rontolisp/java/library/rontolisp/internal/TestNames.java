// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.PreconditionViolationException;

// The display names of the runs of a parameterized test, as JUnit Jupiter 6 makes them
// (measured, 6.0.3): the pattern of its `name`, its placeholders ({index}, {arguments},
// {argumentsWithNames}, {argumentSetName}, {argumentSetNameOrArgumentsWithNames},
// {displayName}) found wherever they are written, quotes or none, and each stretch between
// them that holds a brace a MessageFormat of the arguments' texts. An argument's text: a
// Named one's name, a text quoted and escaped, a character too, an array by its elements,
// anything else as it writes itself, each cut at 512 characters; the arguments are those
// of the row the test's parameters take. No argument is named: a program's classes keep
// no parameter name, as javac with no -parameters. Also what JUnit does to every display
// name: a line break written as <CR> or <LF>, any other control character as U+FFFD.
public final class TestNames {

    private static final String DEFAULT = "[{index}] {argumentSetNameOrArgumentsWithNames}";

    private static final String[] PLACEHOLDERS = { "{index}", "{arguments}", "{argumentsWithNames}",
            "{argumentSetName}", "{argumentSetNameOrArgumentsWithNames}", "{displayName}" };

    private static final int LONGEST = 512;

    // The pattern's stretches in order: a text as it is, a MessageFormat, or the place in
    // PLACEHOLDERS of a placeholder.
    private final List<Object> parts = new ArrayList<>();

    private final boolean quoted;

    private final String displayName;

    private final int parameters;

    // A test's pattern, checked: one that is blank, or a stretch of it that is no
    // MessageFormat, is the test's error before its sources are read. method: the test's
    // method as Method.toGenericString writes it; parameters: how many it has.
    public TestNames(String pattern, boolean quoted, String method, String displayName, int parameters) {
        if (pattern.equals("{default_display_name}")) {
            pattern = DEFAULT;
        }
        if (pattern.isBlank()) {
            throw new PreconditionViolationException("Configuration error: @ParameterizedTest on method [" + method
                    + "] must be declared with a non-empty name.");
        }
        this.quoted = quoted;
        this.displayName = displayName;
        this.parameters = parameters;
        int from = 0;
        int at = 0;
        while (at < pattern.length()) {
            int found = pattern.charAt(at) == '{' ? placeholder(pattern, at) : -1;
            if (found < 0) {
                at++;
                continue;
            }
            text(pattern.substring(from, at));
            this.parts.add(found);
            at += PLACEHOLDERS[found].length();
            from = at;
        }
        text(pattern.substring(from));
    }

    private static int placeholder(String pattern, int at) {
        for (int i = 0; i < PLACEHOLDERS.length; i++) {
            if (pattern.startsWith(PLACEHOLDERS[i], at)) {
                return i;
            }
        }
        return -1;
    }

    private void text(String text) {
        if (text.indexOf('{') < 0) {
            this.parts.add(text);
            return;
        }
        try {
            this.parts.add(new MessageFormat(text));
        }
        catch (IllegalArgumentException ex) {
            throw new JUnitException(
                    "The display name pattern defined for the parameterized test is invalid. See nested exception for further details.",
                    ex);
        }
    }

    // The display name of a run. index: its place among the runs, from 1; set: the name
    // of the argument set its row is, or null.
    public String name(int index, Object[] row, String set) {
        String[] texts = new String[Math.min(row.length, this.parameters)];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = row[i] instanceof TestSources.Headed headed ? headed.header + " = " + argument(headed.getPayload())
                    : row[i] instanceof Named<?> named ? cut(String.valueOf(named.getName())) : argument(row[i]);
        }
        String arguments = String.join(", ", texts);
        StringBuilder name = new StringBuilder();
        for (Object part : this.parts) {
            if (part instanceof String text) {
                name.append(text);
            }
            else if (part instanceof MessageFormat format) {
                name.append(format.format(texts));
            }
            else {
                switch ((Integer) part) {
                    case 0 -> name.append(index);
                    case 1, 2 -> name.append(arguments);
                    case 3 -> {
                        if (set == null) {
                            throw new JUnitException(
                                    "Failed to format display name for parameterized test. See nested exception for further details.",
                                    new ExtensionConfigurationException(
                                            "When the display name pattern for a @ParameterizedTest contains {argumentSetName}, the arguments must be supplied as an ArgumentSet."));
                        }
                        name.append(set);
                    }
                    case 4 -> name.append(set != null ? set : arguments);
                    default -> name.append(this.displayName);
                }
            }
        }
        return sanitized(name.toString());
    }

    private String argument(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence text) {
            String cut = cut(text.toString());
            return this.quoted ? quote(cut, '"') : cut;
        }
        if (value instanceof Character c) {
            return this.quoted ? quote(String.valueOf(c), '\'') : String.valueOf(c);
        }
        return cut(written(value));
    }

    // An object's text as it writes itself, an array by its elements; one whose toString
    // throws by its class and identity, as Object.toString writes it.
    private static String written(Object value) {
        String text;
        try {
            if (value instanceof Object[] array) {
                text = Arrays.deepToString(array);
            }
            else if (value instanceof int[] array) {
                text = Arrays.toString(array);
            }
            else if (value instanceof long[] array) {
                text = Arrays.toString(array);
            }
            else if (value instanceof double[] array) {
                text = Arrays.toString(array);
            }
            else if (value instanceof char[] array) {
                text = Arrays.toString(array);
            }
            else if (value instanceof boolean[] array) {
                text = Arrays.toString(array);
            }
            else if (value instanceof byte[] array) {
                text = Arrays.toString(array);
            }
            else if (value instanceof short[] array) {
                text = Arrays.toString(array);
            }
            else if (value instanceof float[] array) {
                text = Arrays.toString(array);
            }
            else {
                text = value.toString();
            }
        }
        catch (Throwable thrown) {
            return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
        }
        return text == null ? "null" : text;
    }

    private static String cut(String text) {
        return text.length() > LONGEST ? text.substring(0, LONGEST - 1) + "…" : text;
    }

    // A text between quotes: the quote, a backslash and \t \b \n \r \f escaped.
    private static String quote(String text, char quote) {
        StringBuilder quoted = new StringBuilder().append(quote);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\t' -> quoted.append("\\t");
                case '\b' -> quoted.append("\\b");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\f' -> quoted.append("\\f");
                case '\\' -> quoted.append("\\\\");
                default -> {
                    if (c == quote) {
                        quoted.append('\\');
                    }
                    quoted.append(c);
                }
            }
        }
        return quoted.append(quote).toString();
    }

    // A @DisplayName as JUnit keeps one: stripped, a blank one the name it stands for
    // (otherwise), then sanitized.
    public static String displayed(String name, String otherwise) {
        String stripped = name.strip();
        return stripped.isEmpty() ? otherwise : sanitized(stripped);
    }

    // A display name as JUnit keeps one: a carriage return <CR>, a line feed <LF>, any
    // other ISO control character U+FFFD.
    public static String sanitized(String name) {
        StringBuilder kept = null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c)) {
                if (kept != null) {
                    kept.append(c);
                }
                continue;
            }
            if (kept == null) {
                kept = new StringBuilder(name.substring(0, i));
            }
            kept.append(c == '\r' ? "<CR>" : c == '\n' ? "<LF>" : "�");
        }
        return kept == null ? name : kept.toString();
    }

    // The argument a parameter JUnit gives -- a TestInfo, a temporary directory -- is
    // given where the row has no argument for it; else two resolvers compete for it, as
    // JUnit says, the resolvers' identities this program's own. parameter: the parameter
    // as a reflected one writes itself; method: as Method.toGenericString writes it.
    public static <T> T alone(Object[] row, int index, String parameter, String method, String resolver, T given) {
        if (row.length > index) {
            throw new ParameterResolutionException("Discovered multiple competing ParameterResolvers for parameter ["
                    + parameter + "] in method [" + method + "]: " + resolver + "@"
                    + Integer.toHexString(System.identityHashCode(new Object()))
                    + ", org.junit.jupiter.params.ParameterizedTestMethodParameterResolver@"
                    + Integer.toHexString(System.identityHashCode(new Object())));
        }
        return given;
    }

    // The rows of a parameterized test's sources, with the name of each that is an
    // argument set: what a run needs where its runs are named.
    public static final class Rows extends ArrayList<Object[]> {

        private final List<String> sets = new ArrayList<>();

        @Override
        public boolean add(Object[] row) {
            this.sets.add(null);
            return super.add(row);
        }

        // A row a method or a field gave.
        void add(Arguments arguments) {
            super.add(arguments.get());
            this.sets.add(arguments instanceof Arguments.ArgumentSet set ? set.getName() : null);
        }

        public String set(int index) {
            return this.sets.get(index);
        }
    }
}
