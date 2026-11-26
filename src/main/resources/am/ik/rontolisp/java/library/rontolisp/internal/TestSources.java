// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.JUnitException;

// The arguments of a parameterized test: the rows its sources give, and each argument
// as the type of its parameter. A named argument is its payload; a text is converted to
// a number, a boolean, a character or an enum constant; a number widens. The sources and
// the arguments are JUnit Jupiter's own classes, which the program's source path holds.
// Also what a test is given and what is closed after it: its temporary directories and
// the arguments that are AutoCloseable.
public final class TestSources {

    private TestSources() {
    }

    // ---- the sources

    public static void values(List<Object[]> rows, ValueSource source) {
        for (short each : source.shorts()) {
            rows.add(new Object[] { each });
        }
        for (byte each : source.bytes()) {
            rows.add(new Object[] { each });
        }
        for (int each : source.ints()) {
            rows.add(new Object[] { each });
        }
        for (long each : source.longs()) {
            rows.add(new Object[] { each });
        }
        for (float each : source.floats()) {
            rows.add(new Object[] { each });
        }
        for (double each : source.doubles()) {
            rows.add(new Object[] { each });
        }
        for (char each : source.chars()) {
            rows.add(new Object[] { each });
        }
        for (boolean each : source.booleans()) {
            rows.add(new Object[] { each });
        }
        for (String each : source.strings()) {
            rows.add(new Object[] { each });
        }
    }

    // The records of a CSV source: its values one record each, or the lines of its text
    // block, where a line starting with # is a comment.
    public static void csv(List<Object[]> rows, CsvSource source) {
        String delimiter = source.delimiterString();
        if (delimiter.isEmpty()) {
            delimiter = source.delimiter() == '\0' ? "," : String.valueOf(source.delimiter());
        }
        List<String> records = new ArrayList<>();
        if (!source.textBlock().isEmpty()) {
            for (String line : source.textBlock().split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    records.add(line);
                }
            }
        }
        else {
            for (String each : source.value()) {
                records.add(each);
            }
        }
        for (String each : records) {
            rows.add(record(each, delimiter, source.quoteCharacter(), source.emptyValue(), source.nullValues(),
                    source.ignoreLeadingAndTrailingWhitespace()));
        }
    }

    // One record: a column between quotes is taken as it is, two quotes in it one; a
    // column with no quote is trimmed, and null where it is empty.
    private static Object[] record(String text, String delimiter, char quote, String emptyValue, String[] nullValues,
            boolean trimmed) {
        List<Object> columns = new ArrayList<>();
        int at = 0;
        int n = text.length();
        while (true) {
            int start = at;
            while (trimmed && start < n && text.charAt(start) <= ' ' && !text.startsWith(delimiter, start)) {
                start++;
            }
            String column;
            boolean quoted = start < n && text.charAt(start) == quote;
            if (quoted) {
                StringBuilder inside = new StringBuilder();
                int i = start + 1;
                while (i < n) {
                    char c = text.charAt(i);
                    if (c == quote) {
                        if (i + 1 < n && text.charAt(i + 1) == quote) {
                            inside.append(quote);
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    inside.append(c);
                    i++;
                }
                if (i >= n) {
                    throw new IllegalArgumentException("a quoted column of the CSV record is not closed: " + text);
                }
                column = inside.length() == 0 ? emptyValue : inside.toString();
                at = text.indexOf(delimiter, i + 1);
            }
            else {
                at = text.indexOf(delimiter, start);
                column = text.substring(start, at < 0 ? n : at);
                if (trimmed) {
                    column = column.strip();
                }
            }
            Object value = !quoted && column.isEmpty() ? null : column;
            for (String each : nullValues) {
                if (each.equals(column)) {
                    value = null;
                }
            }
            columns.add(value);
            if (at < 0) {
                break;
            }
            at += delimiter.length();
        }
        return columns.toArray();
    }

    public static <E extends Enum<E>> void constants(List<Object[]> rows, E[] all, String[] names, EnumSource.Mode mode) {
        if (mode == EnumSource.Mode.INCLUDE || mode == EnumSource.Mode.EXCLUDE) {
            for (String name : names) {
                boolean known = false;
                for (E each : all) {
                    known |= each.name().equals(name);
                }
                if (!known) {
                    throw new IllegalArgumentException("Invalid enum constant name(s) in @EnumSource: " + name);
                }
            }
        }
        for (E each : all) {
            boolean picked;
            if (names.length == 0) {
                picked = true;
            }
            else if (mode == EnumSource.Mode.INCLUDE || mode == EnumSource.Mode.EXCLUDE) {
                boolean named = false;
                for (String name : names) {
                    named |= each.name().equals(name);
                }
                picked = named == (mode == EnumSource.Mode.INCLUDE);
            }
            else {
                int matched = 0;
                for (String name : names) {
                    if (each.name().matches(name)) {
                        matched++;
                    }
                }
                picked = mode == EnumSource.Mode.MATCH_ALL ? matched == names.length
                        : mode == EnumSource.Mode.MATCH_ANY ? matched > 0 : matched == 0;
            }
            if (picked) {
                rows.add(new Object[] { each });
            }
        }
    }

    // ---- what a method gives

    private static void row(List<Object[]> rows, Object provided) {
        if (provided instanceof Arguments arguments) {
            rows.add(arguments.get());
        }
        else if (provided instanceof Object[] array) {
            rows.add(array);
        }
        else {
            rows.add(new Object[] { provided });
        }
    }

    public static void rows(List<Object[]> rows, Stream<?> provided) {
        Iterator<?> each = provided.iterator();
        while (each.hasNext()) {
            row(rows, each.next());
        }
    }

    public static void rows(List<Object[]> rows, Iterable<?> provided) {
        for (Object each : provided) {
            row(rows, each);
        }
    }

    public static void rows(List<Object[]> rows, Object[] provided) {
        for (Object each : provided) {
            row(rows, each);
        }
    }

    public static void rows(List<Object[]> rows, IntStream provided) {
        for (int each : provided.toArray()) {
            rows.add(new Object[] { each });
        }
    }

    public static void rows(List<Object[]> rows, LongStream provided) {
        for (long each : provided.toArray()) {
            rows.add(new Object[] { each });
        }
    }

    public static void rows(List<Object[]> rows, DoubleStream provided) {
        for (double each : provided.toArray()) {
            rows.add(new Object[] { each });
        }
    }

    // ---- an argument as its parameter's type

    public static Object object(Object[] row, int index) {
        if (index >= row.length) {
            throw new IllegalArgumentException(
                    "No argument for the parameter at index " + index + ": the source gives " + row.length);
        }
        return row[index] instanceof Named<?> named ? named.getPayload() : row[index];
    }

    public static boolean isNull(Object[] row, int index) {
        return object(row, index) == null;
    }

    private static Object primitive(Object[] row, int index, String type) {
        Object value = object(row, index);
        if (value == null) {
            throw new IllegalArgumentException(
                    "The argument at index " + index + " is null, which a parameter of the primitive type " + type
                            + " cannot take");
        }
        return value;
    }

    private static IllegalArgumentException unconvertible(Object value, String type) {
        return new IllegalArgumentException("No implicit conversion to convert object of type " + value.getClass().getName()
                + " to type " + type);
    }

    public static String string(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw unconvertible(value, "java.lang.String");
    }

    public static int toInt(Object[] row, int index) {
        Object value = primitive(row, index, "int");
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Short number) {
            return number;
        }
        if (value instanceof Byte number) {
            return number;
        }
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof String text) {
            return Integer.decode(text);
        }
        throw unconvertible(value, "int");
    }

    public static long toLong(Object[] row, int index) {
        Object value = primitive(row, index, "long");
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof String text) {
            return Long.decode(text);
        }
        return toInt(row, index);
    }

    public static short toShort(Object[] row, int index) {
        Object value = primitive(row, index, "short");
        if (value instanceof Short number) {
            return number;
        }
        if (value instanceof Byte number) {
            return number;
        }
        if (value instanceof String text) {
            return Short.decode(text);
        }
        throw unconvertible(value, "short");
    }

    public static byte toByte(Object[] row, int index) {
        Object value = primitive(row, index, "byte");
        if (value instanceof Byte number) {
            return number;
        }
        if (value instanceof String text) {
            return Byte.decode(text);
        }
        throw unconvertible(value, "byte");
    }

    public static double toDouble(Object[] row, int index) {
        Object value = primitive(row, index, "double");
        if (value instanceof Double number) {
            return number;
        }
        if (value instanceof Float number) {
            return number;
        }
        if (value instanceof String text) {
            return Double.parseDouble(text);
        }
        return toLong(row, index);
    }

    public static float toFloat(Object[] row, int index) {
        Object value = primitive(row, index, "float");
        if (value instanceof Float number) {
            return number;
        }
        if (value instanceof String text) {
            return Float.parseFloat(text);
        }
        return toLong(row, index);
    }

    public static char toChar(Object[] row, int index) {
        Object value = primitive(row, index, "char");
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof String text && text.length() == 1) {
            return text.charAt(0);
        }
        throw unconvertible(value, "char");
    }

    public static boolean toBoolean(Object[] row, int index) {
        Object value = primitive(row, index, "boolean");
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return text.equalsIgnoreCase("true");
        }
        throw unconvertible(value, "boolean");
    }

    // An enum constant: the argument itself, or the constant a text names.
    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> E constant(Object[] row, int index, E[] all) {
        Object value = object(row, index);
        if (value instanceof String text) {
            for (E each : all) {
                if (each.name().equals(text)) {
                    return each;
                }
            }
            throw new IllegalArgumentException("No enum constant " + text);
        }
        return (E) value;
    }

    // ---- what is made for a test and closed after it

    // A temporary directory of its own, under the host's directory for them.
    public static Path directory() throws IOException {
        return Files.createTempDirectory("junit-");
    }

    // Closes what a test was given, after what runs after it: the arguments of its row
    // that are AutoCloseable, the last first. What a close throws is the cause of one
    // error, later ones suppressed in it.
    public static Throwable closed(Throwable thrown, Object[] row) {
        return failed(thrown, closing(null, row));
    }

    // Closes the arguments of a row, where there is one, then deletes the temporary
    // directories of a test or of a class, the last first: an overload of its own, so
    // that a program with no temporary directory holds nothing of the files.
    public static Throwable closed(Throwable thrown, Object[] row, Path[] directories) {
        Throwable closing = closing(null, row);
        for (int i = directories.length - 1; i >= 0; i--) {
            if (directories[i] != null) {
                try {
                    delete(directories[i]);
                }
                catch (Throwable caught) {
                    closing = TestRun.then(closing, caught);
                }
            }
        }
        return failed(thrown, closing);
    }

    private static Throwable closing(Throwable closing, Object[] row) {
        for (int i = row == null ? -1 : row.length - 1; i >= 0; i--) {
            Object argument = row[i] instanceof Named<?> named ? named.getPayload() : row[i];
            if (argument instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                }
                catch (Throwable caught) {
                    closing = TestRun.then(closing, caught);
                }
            }
        }
        return closing;
    }

    private static Throwable failed(Throwable thrown, Throwable closing) {
        return closing == null ? thrown
                : TestRun.then(thrown, new JUnitException("Failed to close extension context", closing));
    }

    // Deletes a file, or a directory and what it holds; a link is deleted, not followed.
    private static void delete(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            // listed before any is deleted, so that no listing reads a changing directory
            List<Path> held = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    held.add(entry);
                }
            }
            for (Path entry : held) {
                delete(entry);
            }
        }
        Files.delete(path);
    }
}
