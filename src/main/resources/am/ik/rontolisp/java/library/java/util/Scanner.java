// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import rontolisp.internal.ObjectFlow;
import rontolisp.internal.StrictReader;

// The input read so far is one string and the position an index in it; what is behind the
// position is dropped when more is read. The default delimiter is no pattern: its tokens are
// cut where Character.isWhitespace is, which is what \p{javaWhitespace} means, so a program
// that names no pattern holds no regular expression. A number is read by the grammar of the
// class documentation, written as code: a numeral of digits or of groups of three after a
// comma, a sign, a decimal point and an exponent, a hexadecimal float, NaN and Infinity.
// Read off a JDK's answers: that any decimal digit is a digit of every radix to the grammar
// (a digit past the radix fails the parse, whose words the refusal then has), that a grouped
// numeral starts with a decimal digit other than 0, that the locale's infinity is taken and
// then refused by the parse, when each operation reads more of the input, where a refused
// token leaves the position (past the delimiters), what a hasNext keeps for the next of its
// kind and what drops it, the match each operation leaves and the scanner's text.
public final class Scanner implements Iterator<String>, Closeable {

    private static final String WHITESPACE = "\\p{javaWhitespace}+";

    // the kinds of number a hasNext keeps
    private static final int INT = 1;

    private static final int LONG = 2;

    private static final int SHORT = 3;

    private static final int BYTE = 4;

    private static final int FLOAT = 5;

    private static final int DOUBLE = 6;

    private static final int BIG_INTEGER = 7;

    private static final int BIG_DECIMAL = 8;

    // where more input comes from, or null for a string
    private Reader source;

    private String buf = "";

    // the position, an index in buf
    private int at;

    // whether the end of the input was met
    private boolean ended;

    private boolean closed;

    private IOException failure;

    private char[] chunk;

    // a high surrogate a read ended in, held for the unit after it
    private int pending = -1;

    // the delimiter delimiter() answers, or null for the default one not yet made
    private Pattern delimiter;

    // whether the delimiter is the default one, found by Character.isWhitespace
    private boolean spaces = true;

    // the delimiter's matcher over buf, until buf or the delimiter changes
    private Matcher delimiting;

    private Locale locale = Locale.getDefault();

    private int radix = 10;

    // what match() answers, or null
    private MatchResult last;

    // What the last hasNext that held found, for the next of its kind: the pattern of a
    // hasNext(Pattern) and the text of a hasNext(String), the token and where it ends
    // (an offset from the position); a number of its kind, read in its radix.
    private Pattern kept;

    private String keptText;

    private String keptToken;

    private MatchResult keptMatch;

    private int keptStart;

    private int keptEnd;

    private int keptKind;

    private int keptRadix;

    private long keptLong;

    private double keptDouble;

    private Object keptBig;

    // the operations the program asked for, which a stream of the scanner checks
    private int changes;

    // the token a search found: offsets from the position
    private int tokenStart;

    private int tokenEnd;

    // the line a search found: where its text ends and how long its separator is
    private int lineEnd;

    private int lineBreak;

    private long parsedLong;

    private double parsedDouble;

    private Object parsedBig;

    private Scanner(Reader source, String text) {
        this.source = source;
        this.buf = text;
    }

    public Scanner(Readable source) {
        this(reader(Objects.requireNonNull(source, "source")), "");
    }

    public Scanner(InputStream source) {
        this(new InputStreamReader(Objects.requireNonNull(source, "source")), "");
    }

    public Scanner(InputStream source, String charsetName) {
        this(new InputStreamReader(Objects.requireNonNull(source, "source"), charset(charsetName)), "");
    }

    public Scanner(InputStream source, Charset charset) {
        this(new InputStreamReader(Objects.requireNonNull(source, "source"), Objects.requireNonNull(charset, "charset")),
                "");
    }

    public Scanner(String source) {
        this((Reader) null, Objects.requireNonNull(source, "source"));
    }

    // A file's bytes are decoded strictly, as a JDK's are: the first that are no text in
    // the charset end the input, and ioException() answers why. A path's are decoded as an
    // InputStream's, what is no text U+FFFD.
    public Scanner(File source) throws FileNotFoundException {
        this(strict(source, Charset.defaultCharset()), "");
    }

    public Scanner(File source, String charsetName) throws FileNotFoundException {
        this(strict(Objects.requireNonNull(source), charset(charsetName)), "");
    }

    public Scanner(File source, Charset charset) throws IOException {
        this(strict(source, Objects.requireNonNull(charset, "charset")), "");
    }

    private static Reader strict(File source, Charset charset) throws FileNotFoundException {
        return new StrictReader(new FileInputStream(source), charset);
    }

    public Scanner(Path source) throws IOException {
        this(Files.newInputStream(source));
    }

    public Scanner(Path source, String charsetName) throws IOException {
        this(Objects.requireNonNull(source), charset(charsetName));
    }

    public Scanner(Path source, Charset charset) throws IOException {
        this(Files.newInputStream(Objects.requireNonNull(source)), charset);
    }

    private static Reader reader(Readable source) {
        return source instanceof Reader ? (Reader) source : new Readables(source);
    }

    private static Charset charset(String name) {
        Objects.requireNonNull(name, "charsetName");
        try {
            return Charset.forName(name);
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // A Readable that is no Reader, read through a buffer of characters.
    private static final class Readables extends Reader {

        private final Readable readable;

        Readables(Readable readable) {
            this.readable = readable;
        }

        public int read(char[] cbuf, int off, int len) throws IOException {
            CharBuffer buffer = CharBuffer.allocate(len);
            int n = readable.read(buffer);
            if (n <= 0) {
                return n;
            }
            buffer.flip();
            int got = buffer.remaining();
            buffer.get(cbuf, off, got);
            return got;
        }

        public void close() throws IOException {
            if (readable instanceof Closeable closeable) {
                closeable.close();
            }
        }
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("Scanner closed");
        }
    }

    // Reads more of the input onto the buffer, which then starts at the position; false at
    // the end of the input. A reader's IOException is kept for ioException and ends the
    // input.
    private boolean fill() {
        if (ended) {
            return false;
        }
        if (source == null) {
            ended = true;
            return false;
        }
        while (true) {
            int want = Math.max(1024, buf.length() - at);
            if (chunk == null || chunk.length < want + 1) {
                chunk = new char[want + 1];
            }
            int from = 0;
            if (pending >= 0) {
                chunk[0] = (char) pending;
                from = 1;
            }
            int n;
            try {
                n = source.read(chunk, from, want);
            }
            catch (IOException e) {
                failure = e;
                n = -1;
            }
            if (n < 0) {
                ended = true;
                if (from == 0) {
                    return false;
                }
                n = 0;
            }
            n += from;
            pending = -1;
            if (!ended && n > 0 && Character.isHighSurrogate(chunk[n - 1])) {
                pending = chunk[n - 1];
                n--;
            }
            if (n > 0) {
                buf = buf.substring(at) + new String(chunk, 0, n);
                at = 0;
                delimiting = null;
                return true;
            }
        }
    }

    // Finds the token after the delimiters at the position: tokenStart and, with whole,
    // tokenEnd. False when the input ends before one.
    private boolean token(boolean whole) {
        if (spaces) {
            int i = 0;
            while (true) {
                while (at + i < buf.length() && space(buf.charAt(at + i))) {
                    i++;
                }
                if (at + i < buf.length() || !fill()) {
                    break;
                }
            }
            tokenStart = i;
            if (at + i >= buf.length()) {
                return false;
            }
            if (whole) {
                while (true) {
                    while (at + i < buf.length() && !space(buf.charAt(at + i))) {
                        i++;
                    }
                    if (at + i < buf.length() || !fill()) {
                        break;
                    }
                }
                tokenEnd = i;
            }
            return true;
        }
        int i;
        while (true) {
            Matcher m = delimiterFrom(at);
            boolean matched = m.lookingAt();
            if (m.hitEnd() && fill()) {
                continue;
            }
            i = matched ? m.end() - at : 0;
            break;
        }
        tokenStart = i;
        if (at + i >= buf.length() && !fill()) {
            return false;
        }
        if (!whole) {
            return true;
        }
        while (true) {
            Matcher m = delimiterFrom(at + i);
            boolean found = m.find();
            if (found && m.end() == at + i) {
                // an empty delimiter where the token starts ends nothing
                found = m.find();
            }
            if (found) {
                if (m.requireEnd() && fill()) {
                    continue;
                }
                tokenEnd = m.start() - at;
            }
            else {
                if (fill()) {
                    continue;
                }
                tokenEnd = buf.length() - at;
            }
            return true;
        }
    }

    // Character.isWhitespace, the ASCII characters decided here
    private static boolean space(char c) {
        return c < 128 ? c == ' ' || (c >= 9 && c <= 13) || (c >= 28 && c <= 31) : Character.isWhitespace(c);
    }

    private Matcher delimiterFrom(int from) {
        if (delimiting == null) {
            delimiting = bounded(delimiter.matcher(buf));
        }
        delimiting.region(from, buf.length());
        return delimiting;
    }

    // The bounds every search of a scanner has: what is around the region is seen, and the
    // region's ends are no ends of the input.
    private static Matcher bounded(Matcher m) {
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        return m;
    }

    private Matcher within(Pattern pattern, int from, int to) {
        Matcher m = bounded(pattern.matcher(buf));
        m.region(from, to);
        return m;
    }

    private String tokenText() {
        return buf.substring(at + tokenStart, at + tokenEnd);
    }

    private MatchResult tokenMatch() {
        return new Found(buf, at + tokenStart, at + tokenEnd, false, at + tokenEnd);
    }

    // Drops what a hasNext kept.
    private void forget() {
        kept = null;
        keptText = null;
        keptToken = null;
        keptMatch = null;
        keptKind = 0;
    }

    // The start of an operation that moves the position.
    private void begin() {
        open();
        forget();
        changes++;
        last = null;
    }

    public boolean hasNext() {
        open();
        last = null;
        return token(false);
    }

    public String next() {
        begin();
        return taken();
    }

    private String taken() {
        if (!token(true)) {
            at += tokenStart;
            throw new NoSuchElementException();
        }
        String token = tokenText();
        last = tokenMatch();
        at += tokenEnd;
        return token;
    }

    public boolean hasNext(Pattern pattern) {
        return hasNextBy(pattern, null);
    }

    private boolean hasNextBy(Pattern pattern, String text) {
        open();
        Objects.requireNonNull(pattern);
        last = null;
        if (!token(true)) {
            return false;
        }
        Matcher m = within(pattern, at + tokenStart, at + tokenEnd);
        if (!m.matches()) {
            return false;
        }
        last = m.toMatchResult();
        kept = pattern;
        keptText = text;
        keptToken = tokenText();
        keptMatch = last;
        keptStart = tokenStart;
        keptEnd = tokenEnd;
        return true;
    }

    public String next(Pattern pattern) {
        return nextBy(pattern, null);
    }

    private String nextBy(Pattern pattern, String text) {
        open();
        Objects.requireNonNull(pattern);
        if (keptToken != null && (text == null ? kept == pattern : text.equals(keptText))) {
            String token = keptToken;
            last = keptMatch;
            at += keptEnd;
            forget();
            changes++;
            return token;
        }
        begin();
        if (!token(true)) {
            at += tokenStart;
            throw new NoSuchElementException();
        }
        Matcher m = within(pattern, at + tokenStart, at + tokenEnd);
        if (!m.matches()) {
            at += tokenStart;
            throw new InputMismatchException();
        }
        last = m.toMatchResult();
        String token = tokenText();
        at += tokenEnd;
        return token;
    }

    // The members taking a pattern's text are native: each is lowered where it is called,
    // the pattern compiled there with the engine's tables its text may need, and calls
    // the static member after it with that pattern (JavaIntrinsics).
    public native boolean hasNext(String pattern);

    static boolean hasNextText(Scanner scanner, String text, Pattern pattern) {
        return scanner.hasNextBy(pattern, text);
    }

    public native String next(String pattern);

    static String nextText(Scanner scanner, String text, Pattern pattern) {
        return scanner.nextBy(pattern, text);
    }

    public native Scanner useDelimiter(String pattern);

    static Scanner delimiterText(Scanner scanner, Pattern pattern) {
        return scanner.useDelimiter(pattern);
    }

    public native String findInLine(String pattern);

    static String findInLineText(Scanner scanner, Pattern pattern) {
        return scanner.findInLine(pattern);
    }

    public native String findWithinHorizon(String pattern, int horizon);

    static String findWithinHorizonText(Scanner scanner, Pattern pattern, int horizon) {
        return scanner.findWithinHorizon(pattern, horizon);
    }

    public native Scanner skip(String pattern);

    static Scanner skipText(Scanner scanner, Pattern pattern) {
        return scanner.skip(pattern);
    }

    public native Stream<MatchResult> findAll(String patString);

    static Stream<MatchResult> findAllText(Scanner scanner, Pattern pattern) {
        return scanner.findAll(pattern);
    }

    private static boolean lineBreak(int c) {
        return c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029;
    }

    // Finds the line at the position: lineEnd and lineBreak. False when nothing is left. A
    // \r the buffer ends in waits for the \n that may follow.
    private boolean line() {
        int i = 0;
        while (true) {
            while (at + i < buf.length() && !lineBreak(buf.charAt(at + i))) {
                i++;
            }
            if (at + i < buf.length()) {
                if (buf.charAt(at + i) == '\r' && at + i + 1 == buf.length() && fill()) {
                    continue;
                }
                lineEnd = i;
                lineBreak = buf.charAt(at + i) == '\r' && at + i + 1 < buf.length() && buf.charAt(at + i + 1) == '\n'
                        ? 2 : 1;
                return true;
            }
            if (!fill()) {
                break;
            }
        }
        if (i == 0) {
            return false;
        }
        lineEnd = i;
        lineBreak = 0;
        return true;
    }

    private MatchResult lineMatch() {
        return new Found(buf, at, at + lineEnd + lineBreak, true, at + lineEnd);
    }

    public boolean hasNextLine() {
        open();
        forget();
        last = null;
        if (!line()) {
            return false;
        }
        last = lineMatch();
        return true;
    }

    public String nextLine() {
        begin();
        if (!line()) {
            throw new NoSuchElementException("No line found");
        }
        last = lineMatch();
        String text = buf.substring(at, at + lineEnd);
        at += lineEnd + lineBreak;
        return text;
    }

    public String findInLine(Pattern pattern) {
        open();
        Objects.requireNonNull(pattern);
        begin();
        int i = 0;
        while (true) {
            while (at + i < buf.length() && !lineBreak(buf.charAt(at + i))) {
                i++;
            }
            if (at + i < buf.length() || !fill()) {
                break;
            }
        }
        return i == 0 ? null : find(pattern, i, 0);
    }

    public String findWithinHorizon(Pattern pattern, int horizon) {
        open();
        Objects.requireNonNull(pattern);
        if (horizon < 0) {
            throw new IllegalArgumentException("horizon < 0");
        }
        begin();
        return find(pattern, horizon, 0);
    }

    // The search within the horizon from the position, 0 for none, starting lead characters
    // further on: more is read where a match, or the lack of one, may change with what
    // follows within the horizon.
    private String find(Pattern pattern, int horizon, int lead) {
        while (true) {
            if (at + lead > buf.length()) {
                if (fill()) {
                    continue;
                }
                return null;
            }
            boolean reaches = horizon == 0 || buf.length() - at < horizon;
            Matcher m = within(pattern, at + lead, reaches ? buf.length() : at + horizon);
            if (m.find()) {
                if (m.hitEnd() && reaches && fill()) {
                    continue;
                }
                last = m.toMatchResult();
                at = m.end();
                return m.group();
            }
            if (reaches && fill()) {
                continue;
            }
            return null;
        }
    }

    public Scanner skip(Pattern pattern) {
        open();
        Objects.requireNonNull(pattern);
        begin();
        while (true) {
            Matcher m = within(pattern, at, buf.length());
            if (m.lookingAt()) {
                if (m.hitEnd() && fill()) {
                    continue;
                }
                last = m.toMatchResult();
                at = m.end();
                return this;
            }
            if (fill()) {
                continue;
            }
            throw new NoSuchElementException();
        }
    }

    // --- numbers and booleans ---------------------------------------------------------

    // Each kind reads its token in its own members, so that a program holds the parses of
    // the kinds it asks for alone.

    private static void radix(int radix) {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new IllegalArgumentException("radix:" + radix);
        }
    }

    // The start of a next of a number: true when what its hasNext kept is taken (into
    // parsedLong, parsedDouble or parsedBig), else the token is found, or
    // NoSuchElementException thrown past the delimiters.
    private boolean reuse(int kind, int radix) {
        radix(radix);
        open();
        if (keptKind == kind && (keptRadix == radix || kind == FLOAT || kind == DOUBLE || kind == BIG_DECIMAL)) {
            parsedLong = keptLong;
            parsedDouble = keptDouble;
            parsedBig = keptBig;
            last = new Found(buf, at + keptStart, at + keptEnd, false, at + keptEnd);
            at += keptEnd;
            forget();
            changes++;
            return true;
        }
        begin();
        if (!token(true)) {
            at += tokenStart;
            throw new NoSuchElementException();
        }
        return false;
    }

    // the token of a next taken: the match is it, the position past it
    private void accept() {
        last = tokenMatch();
        at += tokenEnd;
    }

    // a token refused: the position stays past the delimiters
    private InputMismatchException mismatch(String message) {
        at += tokenStart;
        return new InputMismatchException(message);
    }

    // The start of a hasNext of a number: whether there is a token; the position stays.
    private boolean peek(int radix) {
        radix(radix);
        open();
        last = null;
        return token(true);
    }

    // keeps a number a hasNext found, for the next of its kind; true
    private boolean keep(int kind, int radix, long longValue, double doubleValue, Object big) {
        last = tokenMatch();
        kept = null;
        keptText = null;
        keptToken = null;
        keptMatch = last;
        keptStart = tokenStart;
        keptEnd = tokenEnd;
        keptKind = kind;
        keptRadix = radix;
        keptLong = longValue;
        keptDouble = doubleValue;
        keptBig = big;
        return true;
    }

    // The token's value when it is a sign and at most 18 ASCII digits of radix 10, which
    // neither the grammar nor a parse need read; else Long.MIN_VALUE.
    private long quick(int radix) {
        int i = at + tokenStart;
        int end = at + tokenEnd;
        if (radix != 10) {
            return Long.MIN_VALUE;
        }
        char c = buf.charAt(i);
        boolean negative = c == '-';
        if (negative || c == '+') {
            i++;
        }
        if (i == end || end - i > 18) {
            return Long.MIN_VALUE;
        }
        long value = 0;
        while (i < end) {
            c = buf.charAt(i++);
            if (c < '0' || c > '9') {
                return Long.MIN_VALUE;
            }
            value = value * 10 + (c - '0');
        }
        return negative ? -value : value;
    }

    // whether c is a digit of the radix to the grammar: any decimal digit, or an ASCII
    // letter below the radix
    private static boolean digit(int c, int radix) {
        return Character.isDigit(c) || (c < 128 && Character.digit(c, radix) >= 0);
    }

    // Where the numeral starting at i ends, or -1: digits, or a decimal digit other than 0
    // with up to two digits after it and then groups of a comma and three digits.
    private static int numeral(String t, int i, int radix) {
        int n = t.length();
        int j = i;
        while (j < n && digit(t.charAt(j), radix)) {
            j++;
        }
        if (j < n && t.charAt(j) == ',' && i < n && Character.isDigit(t.charAt(i)) && t.charAt(i) != '0') {
            int g = i + 1;
            if (g < n && digit(t.charAt(g), radix)) {
                g++;
            }
            if (g < n && digit(t.charAt(g), radix)) {
                g++;
            }
            int groups = 0;
            while (g + 4 <= n && t.charAt(g) == ',' && digit(t.charAt(g + 1), radix) && digit(t.charAt(g + 2), radix)
                    && digit(t.charAt(g + 3), radix)) {
                g += 4;
                groups++;
            }
            if (groups > 0) {
                return g;
            }
        }
        return j > i ? j : -1;
    }

    private static int signed(String t) {
        return !t.isEmpty() && (t.charAt(0) == '-' || t.charAt(0) == '+') ? 1 : 0;
    }

    // What an integer's parse takes of a token, its commas dropped, or null.
    private static String integral(String t, int radix) {
        if (numeral(t, signed(t), radix) != t.length()) {
            return null;
        }
        return t.indexOf(',') < 0 ? t : t.replace(",", "");
    }

    private static int decimalDigits(String t, int i) {
        while (i < t.length() && Character.isDigit(t.charAt(i))) {
            i++;
        }
        return i;
    }

    // What BigDecimal takes of a token of the decimal grammar -- its commas dropped, its
    // digits ASCII -- or null.
    private static String decimal(String t) {
        int n = t.length();
        int i = signed(t);
        int j = numeral(t, i, 10);
        if (j < 0) {
            if (i >= n || t.charAt(i) != '.') {
                return null;
            }
            j = decimalDigits(t, i + 1);
            if (j == i + 1) {
                return null;
            }
        }
        else if (j < n && t.charAt(j) == '.') {
            j = decimalDigits(t, j + 1);
        }
        if (j < n && (t.charAt(j) == 'e' || t.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (t.charAt(k) == '+' || t.charAt(k) == '-')) {
                k++;
            }
            j = decimalDigits(t, k);
            if (j == k) {
                return null;
            }
        }
        if (j != n) {
            return null;
        }
        boolean plain = true;
        for (int k = 0; k < n && plain; k++) {
            plain = t.charAt(k) != ',' && t.charAt(k) < 128;
        }
        if (plain) {
            return t;
        }
        StringBuilder text = new StringBuilder(n);
        for (int k = 0; k < n; k++) {
            char c = t.charAt(k);
            if (c >= 128) {
                text.append((char) ('0' + Character.digit(c, 10)));
            }
            else if (c != ',') {
                text.append(c);
            }
        }
        return text.toString();
    }

    private static boolean hex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // whether a token is a hexadecimal float of the grammar, whose point is not optional
    private static boolean hexFloat(String t) {
        int n = t.length();
        int i = signed(t);
        if (i + 2 > n || t.charAt(i) != '0' || (t.charAt(i + 1) != 'x' && t.charAt(i + 1) != 'X')) {
            return false;
        }
        int j = i + 2;
        while (j < n && hex(t.charAt(j))) {
            j++;
        }
        if (j >= n || t.charAt(j) != '.') {
            return false;
        }
        int k = j + 1;
        while (k < n && hex(t.charAt(k))) {
            k++;
        }
        if (k == j + 1) {
            return false;
        }
        if (k < n && (t.charAt(k) == 'p' || t.charAt(k) == 'P')) {
            int e = k + 1;
            if (e < n && (t.charAt(e) == '+' || t.charAt(e) == '-')) {
                e++;
            }
            k = e;
            while (k < n && t.charAt(k) >= '0' && t.charAt(k) <= '9') {
                k++;
            }
            if (k == e) {
                return false;
            }
        }
        return k == n;
    }

    // What a float's parse takes of a token, or null: a decimal as BigDecimal takes it, a
    // hexadecimal float, NaN or an infinity -- the locale's (U+221E), which the parse
    // refuses, as Infinity -- as it is.
    private static String floating(String t) {
        String text = decimal(t);
        if (text != null) {
            return text;
        }
        if (hexFloat(t)) {
            return t;
        }
        String rest = t.substring(signed(t));
        return rest.equals("NaN") || rest.equals("Infinity") || rest.equals("\u221e") ? t : null;
    }

    // true or false in any case of ASCII
    private static boolean bool(String t) {
        String word = t.length() == 4 ? "true" : t.length() == 5 ? "false" : null;
        if (word == null) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            int c = t.charAt(i);
            if (c != word.charAt(i) && c != word.charAt(i) - 32) {
                return false;
            }
        }
        return true;
    }

    public boolean hasNextBoolean() {
        open();
        last = null;
        if (!token(true) || !bool(tokenText())) {
            return false;
        }
        last = tokenMatch();
        return true;
    }

    public boolean nextBoolean() {
        begin();
        if (!token(true)) {
            at += tokenStart;
            throw new NoSuchElementException();
        }
        String token = tokenText();
        if (!bool(token)) {
            throw mismatch(null);
        }
        accept();
        return token.length() == 4;
    }

    public boolean hasNextByte() {
        return hasNextByte(radix);
    }

    public boolean hasNextByte(int radix) {
        if (!peek(radix)) {
            return false;
        }
        long quick = quick(radix);
        if (quick >= Byte.MIN_VALUE && quick <= Byte.MAX_VALUE) {
            return keep(BYTE, radix, quick, 0, null);
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            return false;
        }
        try {
            return keep(BYTE, radix, Byte.parseByte(text, radix), 0, null);
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public byte nextByte() {
        return nextByte(radix);
    }

    public byte nextByte(int radix) {
        if (reuse(BYTE, radix)) {
            return (byte) parsedLong;
        }
        long quick = quick(radix);
        if (quick >= Byte.MIN_VALUE && quick <= Byte.MAX_VALUE) {
            accept();
            return (byte) quick;
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            throw mismatch(null);
        }
        try {
            byte value = Byte.parseByte(text, radix);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    public boolean hasNextShort() {
        return hasNextShort(radix);
    }

    public boolean hasNextShort(int radix) {
        if (!peek(radix)) {
            return false;
        }
        long quick = quick(radix);
        if (quick >= Short.MIN_VALUE && quick <= Short.MAX_VALUE) {
            return keep(SHORT, radix, quick, 0, null);
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            return false;
        }
        try {
            return keep(SHORT, radix, Short.parseShort(text, radix), 0, null);
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public short nextShort() {
        return nextShort(radix);
    }

    public short nextShort(int radix) {
        if (reuse(SHORT, radix)) {
            return (short) parsedLong;
        }
        long quick = quick(radix);
        if (quick >= Short.MIN_VALUE && quick <= Short.MAX_VALUE) {
            accept();
            return (short) quick;
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            throw mismatch(null);
        }
        try {
            short value = Short.parseShort(text, radix);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    public boolean hasNextInt() {
        return hasNextInt(radix);
    }

    public boolean hasNextInt(int radix) {
        if (!peek(radix)) {
            return false;
        }
        long quick = quick(radix);
        if (quick >= Integer.MIN_VALUE && quick <= Integer.MAX_VALUE) {
            return keep(INT, radix, quick, 0, null);
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            return false;
        }
        try {
            return keep(INT, radix, Integer.parseInt(text, radix), 0, null);
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public int nextInt() {
        return nextInt(radix);
    }

    public int nextInt(int radix) {
        if (reuse(INT, radix)) {
            return (int) parsedLong;
        }
        long quick = quick(radix);
        if (quick >= Integer.MIN_VALUE && quick <= Integer.MAX_VALUE) {
            accept();
            return (int) quick;
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            throw mismatch(null);
        }
        try {
            int value = Integer.parseInt(text, radix);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    public boolean hasNextLong() {
        return hasNextLong(radix);
    }

    public boolean hasNextLong(int radix) {
        if (!peek(radix)) {
            return false;
        }
        long quick = quick(radix);
        if (quick != Long.MIN_VALUE) {
            return keep(LONG, radix, quick, 0, null);
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            return false;
        }
        try {
            return keep(LONG, radix, Long.parseLong(text, radix), 0, null);
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public long nextLong() {
        return nextLong(radix);
    }

    public long nextLong(int radix) {
        if (reuse(LONG, radix)) {
            return parsedLong;
        }
        long quick = quick(radix);
        if (quick != Long.MIN_VALUE) {
            accept();
            return quick;
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            throw mismatch(null);
        }
        try {
            long value = Long.parseLong(text, radix);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    public boolean hasNextFloat() {
        if (!peek(radix)) {
            return false;
        }
        String text = floating(tokenText());
        if (text == null) {
            return false;
        }
        try {
            return keep(FLOAT, radix, 0, Float.parseFloat(text), null);
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public float nextFloat() {
        if (reuse(FLOAT, radix)) {
            return (float) parsedDouble;
        }
        String text = floating(tokenText());
        if (text == null) {
            throw mismatch(null);
        }
        try {
            float value = Float.parseFloat(text);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    public boolean hasNextDouble() {
        if (!peek(radix)) {
            return false;
        }
        String text = floating(tokenText());
        if (text == null) {
            return false;
        }
        try {
            return keep(DOUBLE, radix, 0, Double.parseDouble(text), null);
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public double nextDouble() {
        if (reuse(DOUBLE, radix)) {
            return parsedDouble;
        }
        String text = floating(tokenText());
        if (text == null) {
            throw mismatch(null);
        }
        try {
            double value = Double.parseDouble(text);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    public boolean hasNextBigInteger() {
        return hasNextBigInteger(radix);
    }

    public boolean hasNextBigInteger(int radix) {
        if (!peek(radix)) {
            return false;
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            return false;
        }
        try {
            return keep(BIG_INTEGER, radix, 0, 0, new BigInteger(text, radix));
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public BigInteger nextBigInteger() {
        return nextBigInteger(radix);
    }

    public BigInteger nextBigInteger(int radix) {
        if (reuse(BIG_INTEGER, radix)) {
            return (BigInteger) parsedBig;
        }
        String text = integral(tokenText(), radix);
        if (text == null) {
            throw mismatch(null);
        }
        try {
            BigInteger value = new BigInteger(text, radix);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    public boolean hasNextBigDecimal() {
        if (!peek(radix)) {
            return false;
        }
        String text = decimal(tokenText());
        if (text == null) {
            return false;
        }
        try {
            return keep(BIG_DECIMAL, radix, 0, 0, new BigDecimal(text));
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    public BigDecimal nextBigDecimal() {
        if (reuse(BIG_DECIMAL, radix)) {
            return (BigDecimal) parsedBig;
        }
        String text = decimal(tokenText());
        if (text == null) {
            throw mismatch(null);
        }
        try {
            BigDecimal value = new BigDecimal(text);
            accept();
            return value;
        }
        catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
    }

    // --- the scanner's state ------------------------------------------------------------

    public Pattern delimiter() {
        if (delimiter == null) {
            delimiter = Pattern.compile(WHITESPACE);
        }
        return delimiter;
    }

    public Scanner useDelimiter(Pattern pattern) {
        spaces = pattern.pattern().equals(WHITESPACE) && pattern.flags() == 0;
        delimiter = pattern;
        delimiting = null;
        return this;
    }

    public Locale locale() {
        return locale;
    }

    // Every locale the subset has writes its numbers as English does.
    public Scanner useLocale(Locale locale) {
        this.locale = Objects.requireNonNull(locale);
        return this;
    }

    public int radix() {
        return radix;
    }

    public Scanner useRadix(int radix) {
        radix(radix);
        this.radix = radix;
        return this;
    }

    public Scanner reset() {
        delimiter = null;
        spaces = true;
        delimiting = null;
        locale = Locale.getDefault();
        radix = 10;
        forget();
        return this;
    }

    public MatchResult match() {
        if (last == null) {
            throw new IllegalStateException("No match result available");
        }
        return last;
    }

    public IOException ioException() {
        return failure;
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }

    public void close() {
        if (closed) {
            return;
        }
        if (source != null) {
            try {
                source.close();
            }
            catch (IOException e) {
                failure = e;
            }
        }
        closed = true;
        ended = true;
    }

    public String toString() {
        return "java.util.Scanner[delimiters=" + (delimiter == null ? WHITESPACE : delimiter.pattern()) + "][position="
                + at + "][match valid=" + (last != null) + "][need input=false][source closed=" + ended
                + "][skipped=false][group separator=\\x{2c}][decimal separator=\\x{2e}][positive prefix=]"
                + "[negative prefix=\\Q-\\E][positive suffix=][negative suffix=][NaN string=\\QNaN\\E]"
                + "[infinity string=\\Q\u221e\\E]";
    }

    // --- streams ------------------------------------------------------------------------

    // The streams walk the scanner and refuse to go on once the program asked it for
    // anything else; closing them closes it.
    public Stream<String> tokens() {
        open();
        Scanner scanner = this;
        int[] expected = {changes};
        return ObjectFlow.over(new Iterator<String>() {
            public boolean hasNext() {
                scanner.walking(expected[0]);
                return scanner.hasNext();
            }

            public String next() {
                scanner.walking(expected[0]);
                String token = scanner.next();
                expected[0] = scanner.changes;
                return token;
            }
        }).onClose(() -> scanner.close());
    }

    public Stream<MatchResult> findAll(Pattern pattern) {
        Objects.requireNonNull(pattern);
        open();
        Scanner scanner = this;
        int[] expected = {changes};
        // where the next search starts past an empty match at the position
        boolean[] stepped = new boolean[1];
        return ObjectFlow.over(new Iterator<MatchResult>() {
            private MatchResult ahead;

            public boolean hasNext() {
                scanner.walking(expected[0]);
                if (ahead == null) {
                    ahead = scanner.found(pattern, stepped);
                    expected[0] = scanner.changes;
                }
                return ahead != null;
            }

            public MatchResult next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                MatchResult found = ahead;
                ahead = null;
                return found;
            }
        }).onClose(() -> scanner.close());
    }

    private void walking(int expected) {
        open();
        if (changes != expected) {
            throw new ConcurrentModificationException();
        }
    }

    // The next match of findAll, or null: after an empty match at the position the search
    // starts a character further on.
    private MatchResult found(Pattern pattern, boolean[] stepped) {
        begin();
        String found = find(pattern, 0, stepped[0] ? 1 : 0);
        if (found == null) {
            return null;
        }
        stepped[0] = found.isEmpty();
        return last;
    }

    // --- the match of a token or a line ---------------------------------------------------

    // A token, whose match has no group, or a line, whose group 1 is its separator; the
    // text is cut from the buffer it was found in when it is asked for.
    private static final class Found implements MatchResult {

        private final String source;

        private final int start;

        private final int end;

        private final boolean line;

        // where a line's separator starts, the end where it has none
        private final int split;

        Found(String source, int start, int end, boolean line, int split) {
            this.source = source;
            this.start = start;
            this.end = end;
            this.line = line;
            this.split = split;
        }

        private void check(int group) {
            if (group < 0 || group > groupCount()) {
                throw new IndexOutOfBoundsException("No group " + group);
            }
        }

        public int start() {
            return start;
        }

        public int start(int group) {
            check(group);
            return group == 0 ? start : split < end ? split : -1;
        }

        public int end() {
            return end;
        }

        public int end(int group) {
            check(group);
            return group == 0 ? end : split < end ? end : -1;
        }

        public String group() {
            return source.substring(start, end);
        }

        public String group(int group) {
            check(group);
            return group == 0 ? group() : split < end ? source.substring(split, end) : null;
        }

        public int groupCount() {
            return line ? 1 : 0;
        }

        public boolean hasMatch() {
            return true;
        }
    }
}
