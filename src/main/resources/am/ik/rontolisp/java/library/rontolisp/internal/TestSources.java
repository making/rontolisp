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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.BaseStream;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvParsingException;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.commons.support.conversion.ConversionException;

// The arguments of a parameterized test: the rows its sources give, and each argument
// as the type of its parameter, as JUnit Jupiter 6 converts it (measured): a named
// argument is its payload; a text is converted to a number, a boolean, a character, or
// by a conversion of its parameter's type the generated code hands over; a number widens
// to a primitive type; what is not converted throws what JUnit throws. The sources, the
// arguments and the exceptions are JUnit Jupiter's own classes, which the program's
// source path holds.
// Also what a test is given and what is closed after it: its temporary directories and
// the arguments that are AutoCloseable.
public final class TestSources {

    private TestSources() {
    }

    // ---- the sources

    // The values of a source: those of the one element written, as JUnit takes no other
    // (measured: none, or two, is an error).
    public static void values(List<Object[]> rows, ValueSource source) {
        values(rows, source, new Class<?>[0]);
    }

    // The same of a source that may give classes: a method of its own, so that a run
    // with no class literal in a source reads no array of them.
    public static void classes(List<Object[]> rows, ValueSource source) {
        values(rows, source, source.classes());
    }

    private static void values(List<Object[]> rows, ValueSource source, Class<?>[] classes) {
        short[] shorts = source.shorts();
        byte[] bytes = source.bytes();
        int[] ints = source.ints();
        long[] longs = source.longs();
        float[] floats = source.floats();
        double[] doubles = source.doubles();
        char[] chars = source.chars();
        boolean[] booleans = source.booleans();
        String[] strings = source.strings();
        int kinds = (shorts.length > 0 ? 1 : 0) + (bytes.length > 0 ? 1 : 0) + (ints.length > 0 ? 1 : 0)
                + (longs.length > 0 ? 1 : 0) + (floats.length > 0 ? 1 : 0) + (doubles.length > 0 ? 1 : 0)
                + (chars.length > 0 ? 1 : 0) + (booleans.length > 0 ? 1 : 0) + (strings.length > 0 ? 1 : 0)
                + (classes.length > 0 ? 1 : 0);
        if (kinds != 1) {
            throw new PreconditionViolationException(
                    "Exactly one type of input must be provided in the @ValueSource annotation, but there were "
                            + kinds);
        }
        for (short each : shorts) {
            rows.add(new Object[] { each });
        }
        for (byte each : bytes) {
            rows.add(new Object[] { each });
        }
        for (int each : ints) {
            rows.add(new Object[] { each });
        }
        for (long each : longs) {
            rows.add(new Object[] { each });
        }
        for (float each : floats) {
            rows.add(new Object[] { each });
        }
        for (double each : doubles) {
            rows.add(new Object[] { each });
        }
        for (char each : chars) {
            rows.add(new Object[] { each });
        }
        for (boolean each : booleans) {
            rows.add(new Object[] { each });
        }
        for (String each : strings) {
            rows.add(new Object[] { each });
        }
        for (Class<?> each : classes) {
            rows.add(new Object[] { each });
        }
    }

    // The records of a CSV source: those of each of its values, or of its text block,
    // where a line starting with its comment character is a comment.
    public static void csv(List<Object[]> rows, CsvSource source) {
        read(rows, source);
    }

    // The same, the first record the headers each argument is named by, as a run's
    // display name shows it. A method of its own, so that a run naming none holds none
    // of it.
    public static void headed(List<Object[]> rows, CsvSource source) {
        List<Object[]> records = new ArrayList<>();
        read(records, source);
        named(rows, records);
    }

    private static void read(List<Object[]> into, CsvSource source) {
        Csv csv = new Csv(source.delimiter(), source.delimiterString(), source.quoteCharacter(), source.emptyValue(),
                source.nullValues(), source.ignoreLeadingAndTrailingWhitespace(), source.maxCharsPerColumn(),
                source.commentCharacter());
        try {
            if (!source.textBlock().isEmpty()) {
                csv.records(into, source.textBlock(), 1, true);
            }
            else {
                String[] values = source.value();
                for (int i = 0; i < values.length; i++) {
                    csv.records(into, values[i], i + 1, false);
                }
            }
        }
        catch (Csv.CsvParseException wrong) {
            throw new CsvParsingException("Failed to parse CSV input configured via " + shown(source), wrong);
        }
    }

    // The records of the files and the resources of a @CsvFileSource, the resources
    // first: found where the program was lowered, each in streams, null where none is.
    public static void files(List<Object[]> rows, CsvFileSource source, java.io.InputStream[] streams) {
        files(rows, source, streams, null);
    }

    // The same, named by their headers (headed).
    public static void headedFiles(List<Object[]> rows, CsvFileSource source, java.io.InputStream[] streams) {
        files(rows, source, streams, new ArrayList<>());
    }

    private static void files(List<Object[]> rows, CsvFileSource source, java.io.InputStream[] streams,
            List<Object[]> headed) {
        String[] resources = source.resources();
        String[] files = source.files();
        if (resources.length == 0 && files.length == 0) {
            throw new PreconditionViolationException("Resources or files must not be empty");
        }
        java.nio.charset.Charset charset;
        try {
            charset = java.nio.charset.Charset.forName(source.encoding());
        }
        catch (Exception ex) {
            throw new PreconditionViolationException("The charset supplied in " + shown(source) + " is invalid", ex);
        }
        Csv csv = new Csv(source.delimiter(), source.delimiterString(), source.quoteCharacter(), source.emptyValue(),
                source.nullValues(), source.ignoreLeadingAndTrailingWhitespace(), source.maxCharsPerColumn(),
                source.commentCharacter());
        List<Object[]> read = headed != null ? headed : rows;
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < resources.length; i++) {
            if (streams[i] == null) {
                throw new PreconditionViolationException(
                        "Classpath resource [" + resources[i] + "] does not exist");
            }
            try (java.io.InputStream in = streams[i]) {
                texts.add(new String(in.readAllBytes(), charset));
            }
            catch (IOException ex) {
                throw new JUnitException("Classpath resource [" + resources[i] + "] could not be read", ex);
            }
        }
        for (String file : files) {
            try {
                texts.add(new String(Files.readAllBytes(Path.of(file)), charset));
            }
            catch (IOException ex) {
                throw new JUnitException("File [" + file + "] could not be read", ex);
            }
        }
        for (String text : texts) {
            List<Object[]> records = new ArrayList<>();
            try {
                csv.records(records, skipped(text, source.numLinesToSkip()), 1 + source.numLinesToSkip(), true);
            }
            catch (Csv.CsvParseException wrong) {
                throw new CsvParsingException("Failed to parse CSV input configured via " + shown(source), wrong);
            }
            if (headed != null) {
                named(rows, records);
            }
            else {
                for (Object[] record : records) {
                    read.add(record);
                }
            }
        }
    }

    // A text past its first lines.
    private static String skipped(String text, int lines) {
        int at = 0;
        for (int i = 0; i < lines && at < text.length(); i++) {
            int end = text.indexOf('\n', at);
            at = end < 0 ? text.length() : end + 1;
        }
        return text.substring(at);
    }

    // Records of which the first holds the headers: each argument of the others named by
    // the header of its column.
    private static void named(List<Object[]> rows, List<Object[]> records) {
        if (records.isEmpty()) {
            return;
        }
        Object[] headers = records.get(0);
        for (int r = 1; r < records.size(); r++) {
            Object[] record = records.get(r);
            if (record.length > headers.length) {
                throw new PreconditionViolationException("The number of columns (" + record.length
                        + ") exceeds the number of supplied headers (" + headers.length + ") in CSV record: "
                        + java.util.Arrays.toString(record));
            }
            Object[] row = new Object[record.length];
            for (int i = 0; i < record.length; i++) {
                row[i] = new Headed(String.valueOf(headers[i]), record[i]);
            }
            rows.add(row);
        }
    }

    // An argument of a CSV record named by its column's header: a run's display name
    // shows it as `header = value`, the value as any argument is shown.
    public static final class Headed implements Named<Object> {

        final String header;

        private final Object payload;

        Headed(String header, Object payload) {
            this.header = header;
            this.payload = payload;
        }

        @Override
        public String getName() {
            return this.header + " = " + this.payload;
        }

        @Override
        public Object getPayload() {
            return this.payload;
        }
    }

    // How JUnit reads CSV (measured, 6.0.3): a record a line, but where a quoted column
    // goes on past it, two quotes in one a quote, an open quote taking the rest of the
    // text; an empty line no record, a line of blanks one null column; a line starting
    // with the comment character a comment, in a text block or a file only. A column is
    // trimmed where the source says so, an empty one null unless quoted (then the empty
    // value), one of the null values null; past the column limit, or with anything but
    // blanks after its closing quote, the text is no CSV.
    static final class Csv {

        final String delimiter;

        final char quote;

        final String emptyValue;

        final String[] nullValues;

        final boolean trimmed;

        final int max;

        final char comment;

        Csv(char delimiter, String delimiterString, char quote, String emptyValue, String[] nullValues,
                boolean trimmed, int max, char comment) {
            this.delimiter = !delimiterString.isEmpty() ? delimiterString
                    : delimiter == '\0' ? "," : String.valueOf(delimiter);
            this.quote = quote;
            this.emptyValue = emptyValue;
            this.nullValues = nullValues;
            this.trimmed = trimmed;
            this.max = max;
            this.comment = comment;
        }

        // What the reader of JUnit's throws, by its words.
        static final class CsvParseException extends RuntimeException {

            CsvParseException(String message, Throwable cause) {
                super(message, cause);
            }
        }

        private int records;

        // The records of a text, its first line numbered line.
        void records(List<Object[]> into, String text, int line, boolean comments) {
            int at = 0;
            int n = text.length();
            while (at < n) {
                char c = text.charAt(at);
                if (c == '\n' || c == '\r') {
                    at += c == '\r' && at + 1 < n && text.charAt(at + 1) == '\n' ? 2 : 1;
                    line++;
                    continue;
                }
                if (comments && c == this.comment) {
                    while (at < n && text.charAt(at) != '\n' && text.charAt(at) != '\r') {
                        at++;
                    }
                    continue;
                }
                int started = line;
                List<Object> columns = new ArrayList<>();
                while (true) {
                    int start = at;
                    while (this.trimmed && start < n && isBlank(text.charAt(start)) && !text.startsWith(this.delimiter, start)) {
                        start++;
                    }
                    boolean quoted = start < n && text.charAt(start) == this.quote;
                    String column;
                    if (quoted) {
                        StringBuilder inside = new StringBuilder();
                        int i = start + 1;
                        while (i < n) {
                            char d = text.charAt(i);
                            if (d == this.quote) {
                                if (i + 1 < n && text.charAt(i + 1) == this.quote) {
                                    inside.append(d);
                                    i += 2;
                                    continue;
                                }
                                break;
                            }
                            if (d == '\n') {
                                line++;
                            }
                            inside.append(d);
                            i++;
                        }
                        at = Math.min(i + 1, n);
                        while (at < n && isBlank(text.charAt(at)) && !text.startsWith(this.delimiter, at)) {
                            at++;
                        }
                        if (at < n && !text.startsWith(this.delimiter, at) && text.charAt(at) != '\n'
                                && text.charAt(at) != '\r') {
                            throw wrong(started, new CsvParseException("Unexpected character after closing quote: '"
                                    + text.substring(at, at + 1) + "' (0x" + hex(text.charAt(at)) + ")", null));
                        }
                        column = inside.toString();
                    }
                    else {
                        at = start;
                        while (at < n && !text.startsWith(this.delimiter, at) && text.charAt(at) != '\n'
                                && text.charAt(at) != '\r') {
                            at++;
                        }
                        column = text.substring(start, at);
                        if (this.trimmed) {
                            column = column.strip();
                        }
                    }
                    if (column.length() > this.max) {
                        throw wrong(started, new CsvParseException("Field at index " + columns.size() + " in record starting at line "
                                + started + " exceeds the max field size of " + this.max + " characters", null));
                    }
                    Object value = column.isEmpty() ? quoted ? this.emptyValue : null : column;
                    for (String each : this.nullValues) {
                        if (each.equals(column)) {
                            value = null;
                        }
                    }
                    columns.add(value);
                    if (at < n && text.startsWith(this.delimiter, at)) {
                        at += this.delimiter.length();
                        continue;
                    }
                    break;
                }
                this.records++;
                into.add(columns.toArray());
            }
        }

        private CsvParseException wrong(int line, CsvParseException cause) {
            return new CsvParseException(this.records == 0 ? "Exception when reading first record"
                    : "Exception when reading record that started in line " + line, cause);
        }

        // a character's code in hexadecimal, as Integer.toHexString writes it
        static String hex(char c) {
            String hex = "";
            int code = c;
            do {
                hex = "0123456789abcdef".substring(code & 15, (code & 15) + 1) + hex;
                code >>>= 4;
            } while (code != 0);
            return hex;
        }

        private static boolean isBlank(char c) {
            return c == ' ' || c == '\t';
        }
    }

    // A CSV source as an annotation writes itself, its elements in the order declared:
    // a JDK writes them in an order of its own, not the same in every run.
    private static String shown(CsvSource source) {
        return "@org.junit.jupiter.params.provider.CsvSource(value=" + texts(source.value()) + ", textBlock="
                + quoted(source.textBlock()) + ", useHeadersInDisplayName=" + source.useHeadersInDisplayName()
                + shownCommon(source.quoteCharacter(), source.delimiter(), source.delimiterString(),
                        source.emptyValue(), source.nullValues(), source.maxCharsPerColumn(),
                        source.ignoreLeadingAndTrailingWhitespace(), source.commentCharacter())
                + ")";
    }

    private static String shown(CsvFileSource source) {
        return "@org.junit.jupiter.params.provider.CsvFileSource(resources=" + texts(source.resources()) + ", files="
                + texts(source.files()) + ", encoding=" + quoted(source.encoding()) + ", useHeadersInDisplayName="
                + source.useHeadersInDisplayName()
                + shownCommon(source.quoteCharacter(), source.delimiter(), source.delimiterString(),
                        source.emptyValue(), source.nullValues(), source.maxCharsPerColumn(),
                        source.ignoreLeadingAndTrailingWhitespace(), source.commentCharacter())
                + ")";
    }

    private static String shownCommon(char quote, char delimiter, String delimiterString, String emptyValue,
            String[] nullValues, int max, boolean trimmed, char comment) {
        return ", quoteCharacter=" + character(quote) + ", delimiter=" + character(delimiter) + ", delimiterString="
                + quoted(delimiterString) + ", emptyValue=" + quoted(emptyValue) + ", nullValues=" + texts(nullValues)
                + ", maxCharsPerColumn=" + max + ", ignoreLeadingAndTrailingWhitespace=" + trimmed
                + ", commentCharacter=" + character(comment);
    }

    private static String texts(String[] values) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            text.append(i == 0 ? "" : ", ").append(quoted(values[i]));
        }
        return text.append('}').toString();
    }

    private static String character(char c) {
        if (c == '\'') {
            return "'\\''";
        }
        return c >= ' ' ? new String(new char[] { '\'', c, '\'' }) : "'\\u" + "0000".substring(Csv.hex(c).length()) + Csv.hex(c) + "'";
    }

    // The constants of an enum an @EnumSource gives: those from `from` to `to` (each the
    // first or the last where empty), then those its names pick by its mode. value: the
    // canonical name of the class its value names; canonical: the enum's.
    public static <E extends Enum<E>> void constants(List<Object[]> rows, E[] all, String[] names, EnumSource.Mode mode,
            String from, String to, String value, String canonical) {
        StringBuilder written = new StringBuilder();
        for (String name : names) {
            written.append(written.isEmpty() ? "" : ", ").append(quoted(name));
        }
        // the annotation as a JDK's Annotation.toString writes it (measured)
        String shown = "@org.junit.jupiter.params.provider.EnumSource(mode=" + mode + ", from=" + quoted(from) + ", to="
                + quoted(to) + ", names={" + written + "}, value=" + value + ".class)";
        int first = from.isEmpty() ? 0 : ordinal(all, from, canonical);
        int last = to.isEmpty() ? all.length - 1 : ordinal(all, to, canonical);
        if (first > last) {
            throw new PreconditionViolationException("Invalid enum range: 'from' (" + from + ") must come before 'to' ("
                    + to + ") in the natural order of enum constants.");
        }
        if (mode == EnumSource.Mode.INCLUDE || mode == EnumSource.Mode.EXCLUDE) {
            for (String name : names) {
                boolean known = false;
                for (int i = first; i <= last; i++) {
                    known |= all[i].name().equals(name);
                }
                if (!known) {
                    // in the order declared: JUnit writes a HashSet of them, in a JDK's
                    // order, which the library's HashSet does not keep
                    StringBuilder valid = new StringBuilder();
                    for (int i = first; i <= last; i++) {
                        valid.append(i == first ? "" : ", ").append(all[i].name());
                    }
                    throw new PreconditionViolationException(
                            "Invalid enum constant name(s) in " + shown + ". Valid names include: [" + valid + "]");
                }
            }
        }
        List<Pattern> patterns = new ArrayList<>();
        if (mode != EnumSource.Mode.INCLUDE && mode != EnumSource.Mode.EXCLUDE) {
            for (String name : names) {
                try {
                    patterns.add(Pattern.compile(name));
                }
                catch (PatternSyntaxException ex) {
                    throw new PreconditionViolationException(
                            "Pattern compilation failed for a regular expression supplied in " + shown, ex);
                }
            }
        }
        for (int i = first; i <= last; i++) {
            E each = all[i];
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
                for (Pattern pattern : patterns) {
                    if (pattern.matcher(each.name()).matches()) {
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

    private static String quoted(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            quoted.append(c == '"' ? "\\\"" : c == '\\' ? "\\\\" : c == '\n' ? "\\n" : c == '\t' ? "\\t" : String.valueOf(c));
        }
        return quoted.append('"').toString();
    }

    // Where a constant of an enum is among its constants, as Enum.valueOf finds it.
    private static <E extends Enum<E>> int ordinal(E[] all, String name, String canonical) {
        for (int i = 0; i < all.length; i++) {
            if (all[i].name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No enum constant " + canonical + "." + name);
    }

    // ---- what a method gives

    private static void row(List<Object[]> rows, Object provided) {
        if (provided instanceof Arguments arguments) {
            if (rows instanceof TestNames.Rows named) {
                named.add(arguments);
            }
            else {
                rows.add(arguments.get());
            }
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

    // ---- what a field gives

    // The rows of a @FieldSource field's value: a collection or another Iterable, an
    // array of objects or of a primitive type, or a Supplier of one of them or of a
    // stream, each element a row. A stream or an iterator, which a run would use up, is
    // refused, as JUnit refuses it. An overload for each type a field is declared of,
    // so that a program reaches only what its fields' types need.
    public static void field(List<Object[]> rows, Iterable<?> value, String field, String type) {
        rows(rows, (Iterable<?>) present(value, field, type));
    }

    public static void field(List<Object[]> rows, Object[] value, String field, String type) {
        rows(rows, (Object[]) present(value, field, type));
    }

    public static void field(List<Object[]> rows, Supplier<?> value, String field, String type) {
        Object supplied = ((Supplier<?>) present(value, field, type)).get();
        if (supplied == null) {
            throw new PreconditionViolationException("Object must not be null");
        }
        if (supplied instanceof Stream<?> stream) {
            rows(rows, stream);
        }
        else if (supplied instanceof IntStream stream) {
            rows(rows, stream);
        }
        else if (supplied instanceof LongStream stream) {
            rows(rows, stream);
        }
        else if (supplied instanceof DoubleStream stream) {
            rows(rows, stream);
        }
        else {
            elements(rows, supplied, field, type);
        }
    }

    // A field of any other type: what its value is decides.
    public static void field(List<Object[]> rows, Object value, String field, String type) {
        if (present(value, field, type) instanceof Supplier<?> supplier) {
            field(rows, supplier, field, type);
            return;
        }
        String of = "The value of field [" + field + "] in class [" + type + "] must ";
        if (value instanceof BaseStream<?, ?>) {
            throw new PreconditionViolationException(of + "not be a stream");
        }
        if (value instanceof Iterator<?>) {
            throw new PreconditionViolationException(of + "not be an Iterator");
        }
        elements(rows, value, field, type);
    }

    private static void elements(List<Object[]> rows, Object value, String field, String type) {
        if (value instanceof Iterable<?> iterable) {
            rows(rows, iterable);
        }
        else if (value instanceof Object[] array) {
            rows(rows, array);
        }
        else if (value instanceof int[] array) {
            for (int each : array) {
                rows.add(new Object[] { each });
            }
        }
        else if (value instanceof long[] array) {
            for (long each : array) {
                rows.add(new Object[] { each });
            }
        }
        else if (value instanceof double[] array) {
            for (double each : array) {
                rows.add(new Object[] { each });
            }
        }
        else if (value instanceof char[] array) {
            for (char each : array) {
                rows.add(new Object[] { each });
            }
        }
        else if (value instanceof boolean[] array) {
            for (boolean each : array) {
                rows.add(new Object[] { each });
            }
        }
        else if (value instanceof byte[] array) {
            for (byte each : array) {
                rows.add(new Object[] { each });
            }
        }
        else if (value instanceof short[] array) {
            for (short each : array) {
                rows.add(new Object[] { each });
            }
        }
        else if (value instanceof float[] array) {
            for (float each : array) {
                rows.add(new Object[] { each });
            }
        }
        else {
            throw new PreconditionViolationException(
                    "The value of field [" + field + "] in class [" + type + "] must be convertible to a Stream");
        }
    }

    private static Object present(Object value, String field, String type) {
        if (value == null) {
            throw new PreconditionViolationException(
                    "The value of field [" + field + "] in class [" + type + "] must not be null");
        }
        return value;
    }

    // ---- an argument as its parameter's type, as JUnit converts it

    public static Object object(Object[] row, int index) {
        if (index >= row.length) {
            throw new IllegalArgumentException(
                    "No argument for the parameter at index " + index + ": the source gives " + row.length);
        }
        return row[index] instanceof Named<?> named ? named.getPayload() : row[index];
    }

    // What JUnit throws where an argument is not converted: the parameter is not
    // resolved, as the conversion failed for the reason given.
    private static ParameterResolutionException unconverted(int index, String message, Throwable cause) {
        return new ParameterResolutionException("Error converting parameter at index " + index + ": " + message,
                new ArgumentConversionException(message, cause));
    }

    // A text a conversion of its type failed to convert, for the reason given.
    private static ParameterResolutionException failed(int index, String text, String type, Throwable cause) {
        String message = "Failed to convert String \"" + text + "\" to type " + type;
        return unconverted(index, message, new ConversionException(message, cause));
    }

    // A value no conversion to a type takes. An array's class by its name (`[C`) where
    // JUnit writes its type's (`char[]`): Class.getTypeName would cost every parameterized
    // test 10 KB of wasm.
    private static ParameterResolutionException unconvertible(int index, Object value, String type) {
        String message = "No built-in converter for source type " + value.getClass().getName()
                + " and target type " + type;
        return unconverted(index, message, value instanceof String ? new ConversionException(message) : null);
    }

    // A conversion of a text to a type: its factory, constructor or parsing method.
    @FunctionalInterface
    public interface Conversion {

        Object from(String text) throws Throwable;
    }

    // An argument as a parameter of a class type: the argument itself where it is one
    // (given: whether it is an instance of the type) or null, else what the type's
    // conversion makes of a text, where it has one. type: its name, as JUnit's messages
    // write it.
    public static Object converted(Object[] row, int index, boolean given, String type, Conversion conversion) {
        Object value = object(row, index);
        if (value == null || given) {
            return value;
        }
        if (value instanceof String text && conversion != null) {
            try {
                return conversion.from(text);
            }
            catch (Throwable thrown) {
                throw failed(index, text, type, thrown);
            }
        }
        throw unconvertible(index, value, type);
    }

    public static String string(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw unconvertible(index, value, "java.lang.String");
    }

    // The primitive types: their own box, a box of a type that widens to them (byte to
    // short, short and char to int, int to long, long to float, float to double), or a
    // text, whose underscores a number drops; never null.
    private static Object primitive(Object[] row, int index, String type) {
        Object value = object(row, index);
        if (value == null) {
            throw unconverted(index, "Cannot convert null to primitive value of type " + type, null);
        }
        return value;
    }

    public static byte toByte(Object[] row, int index) {
        Object value = primitive(row, index, "byte");
        if (value instanceof Byte number) {
            return number;
        }
        if (value instanceof String text) {
            return parseByte(index, text, "byte");
        }
        throw unconvertible(index, value, "byte");
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
            return parseShort(index, text, "short");
        }
        throw unconvertible(index, value, "short");
    }

    public static char toChar(Object[] row, int index) {
        Object value = primitive(row, index, "char");
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof String text) {
            return parseChar(index, text, "char");
        }
        throw unconvertible(index, value, "char");
    }

    public static int toInt(Object[] row, int index) {
        Object value = primitive(row, index, "int");
        if (value instanceof String text) {
            return parseInt(index, text, "int");
        }
        Integer widened = widenedInt(value);
        if (widened != null) {
            return widened;
        }
        throw unconvertible(index, value, "int");
    }

    private static Integer widenedInt(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Short number) {
            return (int) number;
        }
        if (value instanceof Byte number) {
            return (int) number;
        }
        if (value instanceof Character character) {
            return (int) character;
        }
        return null;
    }

    public static long toLong(Object[] row, int index) {
        Object value = primitive(row, index, "long");
        if (value instanceof String text) {
            return parseLong(index, text, "long");
        }
        Long widened = widenedLong(value);
        if (widened != null) {
            return widened;
        }
        throw unconvertible(index, value, "long");
    }

    private static Long widenedLong(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        Integer widened = widenedInt(value);
        return widened == null ? null : (long) widened;
    }

    public static float toFloat(Object[] row, int index) {
        Object value = primitive(row, index, "float");
        if (value instanceof String text) {
            return parseFloat(index, text, "float");
        }
        if (value instanceof Float number) {
            return number;
        }
        Long widened = widenedLong(value);
        if (widened != null) {
            return widened;
        }
        throw unconvertible(index, value, "float");
    }

    public static double toDouble(Object[] row, int index) {
        Object value = primitive(row, index, "double");
        if (value instanceof String text) {
            return parseDouble(index, text, "double");
        }
        if (value instanceof Double number) {
            return number;
        }
        if (value instanceof Float number) {
            return number;
        }
        Long widened = widenedLong(value);
        if (widened != null) {
            return widened;
        }
        throw unconvertible(index, value, "double");
    }

    public static boolean toBoolean(Object[] row, int index) {
        Object value = primitive(row, index, "boolean");
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return parseBoolean(index, text, "boolean");
        }
        throw unconvertible(index, value, "boolean");
    }

    // The boxes: the box itself, null or a text, never a box of another type.
    public static Byte byteBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Byte) {
            return (Byte) value;
        }
        if (value instanceof String text) {
            return parseByte(index, text, "java.lang.Byte");
        }
        throw unconvertible(index, value, "java.lang.Byte");
    }

    public static Short shortBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Short) {
            return (Short) value;
        }
        if (value instanceof String text) {
            return parseShort(index, text, "java.lang.Short");
        }
        throw unconvertible(index, value, "java.lang.Short");
    }

    public static Character charBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Character) {
            return (Character) value;
        }
        if (value instanceof String text) {
            return parseChar(index, text, "java.lang.Character");
        }
        throw unconvertible(index, value, "java.lang.Character");
    }

    public static Integer intBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof String text) {
            return parseInt(index, text, "java.lang.Integer");
        }
        throw unconvertible(index, value, "java.lang.Integer");
    }

    public static Long longBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof String text) {
            return parseLong(index, text, "java.lang.Long");
        }
        throw unconvertible(index, value, "java.lang.Long");
    }

    public static Float floatBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Float) {
            return (Float) value;
        }
        if (value instanceof String text) {
            return parseFloat(index, text, "java.lang.Float");
        }
        throw unconvertible(index, value, "java.lang.Float");
    }

    public static Double doubleBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof String text) {
            return parseDouble(index, text, "java.lang.Double");
        }
        throw unconvertible(index, value, "java.lang.Double");
    }

    public static Boolean booleanBox(Object[] row, int index) {
        Object value = object(row, index);
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String text) {
            return parseBoolean(index, text, "java.lang.Boolean");
        }
        throw unconvertible(index, value, "java.lang.Boolean");
    }

    // ---- a text as a number, a character or a boolean

    private static byte parseByte(int index, String text, String type) {
        try {
            return Byte.decode(text.replace("_", ""));
        }
        catch (RuntimeException ex) {
            throw failed(index, text, type, ex);
        }
    }

    private static short parseShort(int index, String text, String type) {
        try {
            return Short.decode(text.replace("_", ""));
        }
        catch (RuntimeException ex) {
            throw failed(index, text, type, ex);
        }
    }

    private static int parseInt(int index, String text, String type) {
        try {
            return Integer.decode(text.replace("_", ""));
        }
        catch (RuntimeException ex) {
            throw failed(index, text, type, ex);
        }
    }

    private static long parseLong(int index, String text, String type) {
        try {
            return Long.decode(text.replace("_", ""));
        }
        catch (RuntimeException ex) {
            throw failed(index, text, type, ex);
        }
    }

    private static float parseFloat(int index, String text, String type) {
        try {
            return Float.parseFloat(text.replace("_", ""));
        }
        catch (RuntimeException ex) {
            throw failed(index, text, type, ex);
        }
    }

    private static double parseDouble(int index, String text, String type) {
        try {
            return Double.parseDouble(text.replace("_", ""));
        }
        catch (RuntimeException ex) {
            throw failed(index, text, type, ex);
        }
    }

    private static char parseChar(int index, String text, String type) {
        if (text.length() != 1) {
            throw failed(index, text, type, new PreconditionViolationException("String must have length of 1: " + text));
        }
        return text.charAt(0);
    }

    private static boolean parseBoolean(int index, String text, String type) {
        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
            throw failed(index, text, type,
                    new PreconditionViolationException("String must be 'true' or 'false' (ignoring case): " + text));
        }
        return text.equalsIgnoreCase("true");
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
        return failed(thrown, deleted(closing(null, row), directories));
    }

    // The same, with the directories deleted only where the run succeeded, those of
    // CleanupMode.ON_SUCCESS (kept), after the others (directories, or null).
    public static Throwable closed(Throwable thrown, Object[] row, Path[] directories, Path[] kept, boolean failed) {
        Throwable closing = closing(null, row);
        if (directories != null) {
            closing = deleted(closing, directories);
        }
        if (!failed) {
            closing = deleted(closing, kept);
        }
        return failed(thrown, closing);
    }

    private static Throwable deleted(Throwable closing, Path[] directories) {
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
        return closing;
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
