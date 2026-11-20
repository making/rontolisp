// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

// A table of texts by text, with a table of defaults below it. The order a program sees
// is what a JDK 25 answers (.kb/java-frontend.md, "Resources"): the table's own keys are
// a ConcurrentHashMap's, of the capacity given if one is; the names of the texts and a
// listing are a HashMap's, propertyNames() a Hashtable's, each filled from the deepest
// defaults up and then from the table's own; store writes the table's own entries by
// their keys' order. The lines load reads and store writes are the ones the API
// documentation states.
public class Properties extends Hashtable<Object, Object> {

    protected volatile Properties defaults;

    private final ConcurrentHashMap<Object, Object> map;

    public Properties() {
        this((Properties) null);
    }

    public Properties(int initialCapacity) {
        super(1);
        this.map = new ConcurrentHashMap<>(initialCapacity);
    }

    public Properties(Properties defaults) {
        super(1);
        this.map = new ConcurrentHashMap<>();
        this.defaults = defaults;
    }

    public synchronized Object setProperty(String key, String value) {
        return put(key, value);
    }

    public String getProperty(String key) {
        Object value = map.get(key);
        String text = value instanceof String ? (String) value : null;
        Properties below = defaults;
        return text == null && below != null ? below.getProperty(key) : text;
    }

    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value == null ? defaultValue : value;
    }

    public Enumeration<?> propertyNames() {
        Hashtable<String, Object> names = new Hashtable<>();
        enumerate(names);
        return names.keys();
    }

    public Set<String> stringPropertyNames() {
        HashMap<String, String> texts = new HashMap<>();
        enumerateTexts(texts);
        return Collections.unmodifiableSet(texts.keySet());
    }

    // every key from the deepest defaults up, each a String
    private void enumerate(Map<String, Object> into) {
        Properties below = defaults;
        if (below != null) {
            below.enumerate(into);
        }
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            into.put((String) entry.getKey(), entry.getValue());
        }
    }

    private void enumerateTexts(Map<String, String> into) {
        Properties below = defaults;
        if (below != null) {
            below.enumerateTexts(into);
        }
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                into.put((String) entry.getKey(), (String) entry.getValue());
            }
        }
    }

    public void list(PrintStream out) {
        out.println("-- listing properties --");
        for (String line : listed()) {
            out.println(line);
        }
    }

    public void list(PrintWriter out) {
        out.println("-- listing properties --");
        for (String line : listed()) {
            out.println(line);
        }
    }

    // a value longer than forty characters shows its first thirty-seven and three dots
    private List<String> listed() {
        HashMap<String, Object> all = new HashMap<>();
        enumerate(all);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            String value = (String) entry.getValue();
            lines.add(entry.getKey() + "=" + (value.length() > 40 ? value.substring(0, 37) + "..." : value));
        }
        return lines;
    }

    // ---- load: the lines of the API documentation

    public synchronized void load(Reader reader) throws IOException {
        Objects.requireNonNull(reader, "reader parameter is null");
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) >= 0) {
            text.append(buffer, 0, read);
        }
        read(text.toString());
    }

    // a stream's octets are the characters of ISO 8859-1
    public synchronized void load(InputStream inStream) throws IOException {
        Objects.requireNonNull(inStream, "inStream parameter is null");
        byte[] octets = inStream.readAllBytes();
        char[] text = new char[octets.length];
        for (int i = 0; i < octets.length; i++) {
            text[i] = (char) (octets[i] & 0xff);
        }
        read(new String(text));
    }

    private static boolean blank(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static boolean ending(char c) {
        return c == '\n' || c == '\r';
    }

    // the logical lines of a text: a comment or a blank natural line is none; a line
    // ending in an odd number of backslashes goes on in the next, whose leading blanks
    // are dropped; a backslash at the end of the text is dropped
    private void read(String text) {
        int length = text.length();
        int at = 0;
        while (at < length) {
            while (at < length && blank(text.charAt(at))) {
                at++;
            }
            if (at == length) {
                break;
            }
            char first = text.charAt(at);
            if (ending(first) || first == '#' || first == '!') {
                while (at < length && !ending(text.charAt(at))) {
                    at++;
                }
                at = pastEnding(text, at);
                continue;
            }
            StringBuilder line = new StringBuilder();
            while (at < length) {
                char c = text.charAt(at);
                if (c == '\\') {
                    if (at + 1 == length) {
                        at++;
                        break;
                    }
                    char next = text.charAt(at + 1);
                    if (ending(next)) {
                        at = pastEnding(text, at + 1);
                        while (at < length && blank(text.charAt(at))) {
                            at++;
                        }
                        continue;
                    }
                    line.append(c).append(next);
                    at += 2;
                    continue;
                }
                if (ending(c)) {
                    at = pastEnding(text, at);
                    break;
                }
                line.append(c);
                at++;
            }
            entry(line.toString());
        }
    }

    // past a line's end: "\n", "\r" or "\r\n"
    private static int pastEnding(String text, int at) {
        if (at < text.length() && text.charAt(at) == '\r') {
            at++;
            if (at < text.length() && text.charAt(at) == '\n') {
                at++;
            }
        }
        else if (at < text.length() && text.charAt(at) == '\n') {
            at++;
        }
        return at;
    }

    // a key ends at its first '=', ':' or blank that no backslash escapes; blanks after
    // it and one '=' or ':' among them are dropped, and the rest is the value
    private void entry(String line) {
        int length = line.length();
        int end = 0;
        boolean separated = false;
        while (end < length) {
            char c = line.charAt(end);
            if (c == '\\') {
                end += 2;
                continue;
            }
            if (c == '=' || c == ':') {
                separated = true;
                break;
            }
            if (blank(c)) {
                break;
            }
            end++;
        }
        int value = Math.min(end, length);
        if (separated) {
            value++;
        }
        else {
            while (value < length && blank(line.charAt(value))) {
                value++;
            }
            if (value < length && (line.charAt(value) == '=' || line.charAt(value) == ':')) {
                value++;
            }
        }
        while (value < length && blank(line.charAt(value))) {
            value++;
        }
        map.put(unescaped(line, 0, Math.min(end, length)), unescaped(line, value, length));
    }

    // a high surrogate's \\uxxxx and a low one's after it are one character: a string's
    // element here is a code point
    private static String unescaped(String line, int from, int to) {
        StringBuilder text = new StringBuilder();
        int at = from;
        int high = -1;
        while (at < to) {
            char c = line.charAt(at++);
            if (c != '\\' || at == to) {
                if (c != '\\') {
                    text.append(c);
                }
                continue;
            }
            c = line.charAt(at++);
            if (c == 'u') {
                int code = 0;
                for (int i = 0; i < 4; i++) {
                    int digit = at < to ? hexDigit(line.charAt(at)) : -1;
                    if (digit < 0) {
                        throw new IllegalArgumentException("Malformed \\uxxxx encoding.");
                    }
                    code = code * 16 + digit;
                    at++;
                }
                if (code >= 0xdc00 && code <= 0xdfff && high >= 0 && high == text.length() - 1
                        && text.charAt(high) >= 0xd800 && text.charAt(high) <= 0xdbff) {
                    int combined = 0x10000 + ((text.charAt(high) - 0xd800) << 10) + (code - 0xdc00);
                    text.setLength(high);
                    text.appendCodePoint(combined);
                    high = -1;
                    continue;
                }
                high = code >= 0xd800 && code <= 0xdbff ? text.length() : -1;
                text.append((char) code);
            }
            else {
                text.append(c == 't' ? '\t' : c == 'n' ? '\n' : c == 'r' ? '\r' : c == 'f' ? '\f' : c);
            }
        }
        return text.toString();
    }

    private static int hexDigit(char c) {
        return c >= '0' && c <= '9' ? c - '0'
                : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    }

    // ---- store: the lines of the API documentation

    public void store(Writer writer, String comments) throws IOException {
        Objects.requireNonNull(writer);
        write(writer, comments, false);
    }

    // in ISO 8859-1, a character outside 0x20 to 0x7e of a key or a value as \\uxxxx
    public void store(OutputStream out, String comments) throws IOException {
        Objects.requireNonNull(out);
        write(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.ISO_8859_1)), comments, true);
    }

    private void write(Writer writer, String comments, boolean latin) throws IOException {
        if (comments != null) {
            writer.write(commented(comments));
            writer.write("\n");
        }
        writer.write("#" + new Date() + "\n");
        synchronized (this) {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                sorted.put((String) entry.getKey(), (String) entry.getValue());
            }
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                writer.write(escaped(entry.getKey(), true, latin) + "=" + escaped(entry.getValue(), false, latin) + "\n");
            }
        }
        writer.flush();
    }

    // a line's end begins a line, with a '#' unless one or a '!' starts it; a character
    // above 0xff as \\uxxxx
    private static String commented(String comments) {
        StringBuilder text = new StringBuilder("#");
        int at = 0;
        int length = comments.length();
        while (at < length) {
            char c = comments.charAt(at);
            if (ending(c)) {
                at = pastEnding(comments, at);
                text.append('\n');
                if (at == length || (comments.charAt(at) != '#' && comments.charAt(at) != '!')) {
                    text.append('#');
                }
                continue;
            }
            if (c > 0xff) {
                text.append(hex(c));
            }
            else {
                text.append(c);
            }
            at++;
        }
        return text.toString();
    }

    // \\uxxxx of each UTF-16 unit of a character: a string's element here is a code point
    private static String hex(char c) {
        if (c > 0xffff) {
            int above = c - 0x10000;
            return hex((char) (0xd800 + (above >> 10))) + hex((char) (0xdc00 + (above & 0x3ff)));
        }
        String digits = Integer.toHexString(c).toUpperCase();
        return "\\u" + "0000".substring(digits.length()) + digits;
    }

    // every space of a key, a value's leading ones, and '#', '!', '=', ':' and '\\'
    private static String escaped(String text, boolean key, boolean latin) {
        StringBuilder written = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case ' ' -> written.append(key || i == 0 ? "\\ " : " ");
                case '\t' -> written.append("\\t");
                case '\n' -> written.append("\\n");
                case '\r' -> written.append("\\r");
                case '\f' -> written.append("\\f");
                case '\\', '#', '!', '=', ':' -> written.append('\\').append(c);
                default -> {
                    if (latin && (c < 0x20 || c > 0x7e)) {
                        written.append(hex(c));
                    }
                    else {
                        written.append(c);
                    }
                }
            }
        }
        return written.toString();
    }

    // ---- the table's own mappings

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public Enumeration<Object> keys() {
        return map.keys();
    }

    public Enumeration<Object> elements() {
        return map.elements();
    }

    public boolean contains(Object value) {
        return map.contains(value);
    }

    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    public boolean containsKey(Object key) {
        return map.containsKey(key);
    }

    public Object get(Object key) {
        return map.get(key);
    }

    public synchronized Object put(Object key, Object value) {
        return map.put(key, value);
    }

    public synchronized Object remove(Object key) {
        return map.remove(key);
    }

    public synchronized void putAll(Map<?, ?> t) {
        map.putAll(t);
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized String toString() {
        return map.toString();
    }

    public Set<Object> keySet() {
        return map.keySet();
    }

    public Collection<Object> values() {
        return map.values();
    }

    public Set<Map.Entry<Object, Object>> entrySet() {
        return map.entrySet();
    }

    public synchronized boolean equals(Object o) {
        return o == this || map.equals(o);
    }

    public synchronized int hashCode() {
        return map.hashCode();
    }

    public Object getOrDefault(Object key, Object defaultValue) {
        return map.getOrDefault(key, defaultValue);
    }

    public synchronized void forEach(BiConsumer<? super Object, ? super Object> action) {
        map.forEach(action);
    }

    public synchronized void replaceAll(BiFunction<? super Object, ? super Object, ?> function) {
        map.replaceAll(function);
    }

    public synchronized Object putIfAbsent(Object key, Object value) {
        return map.putIfAbsent(key, value);
    }

    public synchronized boolean remove(Object key, Object value) {
        return map.remove(key, value);
    }

    public synchronized boolean replace(Object key, Object oldValue, Object newValue) {
        return map.replace(key, oldValue, newValue);
    }

    public synchronized Object replace(Object key, Object value) {
        return map.replace(key, value);
    }

    public synchronized Object computeIfAbsent(Object key, Function<? super Object, ?> mappingFunction) {
        return map.computeIfAbsent(key, mappingFunction);
    }

    public synchronized Object computeIfPresent(Object key,
            BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        return map.computeIfPresent(key, remappingFunction);
    }

    public synchronized Object compute(Object key, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        return map.compute(key, remappingFunction);
    }

    public synchronized Object merge(Object key, Object value,
            BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        return map.merge(key, value, remappingFunction);
    }
}
