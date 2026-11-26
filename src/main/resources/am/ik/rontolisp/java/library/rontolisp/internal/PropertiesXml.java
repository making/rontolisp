// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.InvalidPropertiesFormatException;
import java.util.Properties;

// Properties.loadFromXML's reader: a document of the properties DTD -- a declaration, the
// DOCTYPE naming the DTD, a root properties holding at most one comment and entries,
// each entry's text under its key -- as the XML 1.0 recommendation reads it, the
// refusals' words and what a JDK 25 makes of a document the DTD does not allow being its
// answers (.kb/java-frontend.md, "Resources"): an entry inside an entry starts a new one,
// text outside an entry is dropped, an entity no declaration names reads as nothing.
public final class PropertiesXml {

    private static final String DTD = "http://java.sun.com/dtd/properties.dtd";

    private static final String NO_DOCTYPE =
            "An XML properties document must contain the DOCTYPE declaration as defined by java.util.Properties.";

    private final String text;

    private final Properties into;

    private int at;

    private int comments;

    private String key;

    private StringBuilder value;

    private PropertiesXml(String text, Properties into) {
        this.text = text;
        this.into = into;
    }

    // the stream read to its end and closed, its octets in the charset its mark, its first
    // octets or its declaration names (UTF-8 where none does), its line ends one '\n' each
    public static void load(Properties into, InputStream in) throws IOException {
        byte[] octets;
        try {
            octets = in.readAllBytes();
        }
        finally {
            in.close();
        }
        String read = decoded(octets);
        StringBuilder lines = new StringBuilder(read.length());
        for (int i = 0; i < read.length(); i++) {
            char c = read.charAt(i);
            if (c == '\r') {
                lines.append('\n');
                if (i + 1 < read.length() && read.charAt(i + 1) == '\n') {
                    i++;
                }
            }
            else {
                lines.append(c);
            }
        }
        new PropertiesXml(lines.toString(), into).document();
    }

    private static String decoded(byte[] b) throws UnsupportedEncodingException {
        int n = b.length;
        if (n >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) {
            return Utf16.big().text(b, 2, n - 2);
        }
        if (n >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) {
            return Utf16.little().text(b, 2, n - 2);
        }
        if (n >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf) {
            return Charset.utf8().text(b, 3, n - 3);
        }
        // UTF-16 with no mark: a '<' and a '?' of two octets each
        if (n >= 4 && b[0] == 0 && b[1] == '<' && b[2] == 0 && b[3] == '?') {
            return Utf16.big().text(b, 0, n);
        }
        if (n >= 4 && b[0] == '<' && b[1] == 0 && b[2] == '?' && b[3] == 0) {
            return Utf16.little().text(b, 0, n);
        }
        String head = Charset.latin1().text(b, 0, Math.min(n, 200));
        int end = head.indexOf("?>");
        if (head.startsWith("<?xml") && end > 0) {
            String named = pseudo(head.substring(0, end), "encoding");
            if (named != null) {
                Charset charset = Charset.find(named);
                if (charset == null) {
                    throw new UnsupportedEncodingException(named.toUpperCase());
                }
                return charset.text(b, 0, n);
            }
        }
        return Charset.utf8().text(b, 0, n);
    }

    // the value of a pseudo-attribute of the declaration, or null
    private static String pseudo(String declaration, String name) {
        int at = declaration.indexOf(name);
        while (at > 0) {
            int i = at + name.length();
            while (i < declaration.length() && blank(declaration.charAt(i))) {
                i++;
            }
            if (i < declaration.length() && declaration.charAt(i) == '=') {
                i++;
                while (i < declaration.length() && blank(declaration.charAt(i))) {
                    i++;
                }
                if (i < declaration.length()) {
                    char quote = declaration.charAt(i);
                    int close = declaration.indexOf(quote, i + 1);
                    if ((quote == '"' || quote == '\'') && close > i) {
                        return declaration.substring(i + 1, close);
                    }
                }
                return null;
            }
            at = declaration.indexOf(name, at + 1);
        }
        return null;
    }

    private static boolean blank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static InvalidPropertiesFormatException invalid(String reason) {
        return new InvalidPropertiesFormatException("jdk.internal.org.xml.sax.SAXParseException; " + reason);
    }

    private void document() throws InvalidPropertiesFormatException {
        if (text.startsWith("<?xml")) {
            int end = text.indexOf("?>");
            if (end < 0 || !"1.0".equals(pseudo(text.substring(0, end), "version"))) {
                throw invalid("");
            }
            at = end + 2;
        }
        misc();
        if (!text.startsWith("<!DOCTYPE", at)) {
            throw invalid(NO_DOCTYPE);
        }
        doctype();
        misc();
        if (!text.startsWith("<", at)) {
            throw invalid("");
        }
        at++;
        element(true);
        misc();
        if (at < text.length()) {
            throw invalid("");
        }
    }

    // spaces, comments and processing instructions
    private void misc() throws InvalidPropertiesFormatException {
        while (at < text.length()) {
            if (blank(text.charAt(at))) {
                at++;
            }
            else if (!skipped()) {
                return;
            }
        }
    }

    // a comment or a processing instruction skipped, if one starts here
    private boolean skipped() throws InvalidPropertiesFormatException {
        String close = text.startsWith("<!--", at) ? "-->" : text.startsWith("<?", at) ? "?>" : null;
        if (close == null) {
            return false;
        }
        int end = text.indexOf(close, at + 2);
        if (end < 0) {
            throw invalid("");
        }
        at = end + close.length();
        return true;
    }

    // <!DOCTYPE properties SYSTEM "the DTD">, or PUBLIC with an identifier before it
    private void doctype() throws InvalidPropertiesFormatException {
        at += "<!DOCTYPE".length();
        spaces();
        String root = name();
        spaces();
        String system = null;
        if (text.startsWith("SYSTEM", at)) {
            at += "SYSTEM".length();
            spaces();
            system = literal();
        }
        else if (text.startsWith("PUBLIC", at)) {
            at += "PUBLIC".length();
            spaces();
            literal();
            spaces();
            system = literal();
        }
        spaces();
        if (text.startsWith("[", at)) {
            throw invalid("Internal DTD subset is not allowed. The Properties XML document must have the following"
                    + " DOCTYPE declaration: \n<!DOCTYPE properties SYSTEM \"" + DTD + "\">");
        }
        if (!text.startsWith(">", at)) {
            throw invalid("");
        }
        at++;
        if (!root.equals("properties") || !DTD.equals(system)) {
            throw invalid(NO_DOCTYPE);
        }
    }

    private void spaces() {
        while (at < text.length() && blank(text.charAt(at))) {
            at++;
        }
    }

    private String literal() throws InvalidPropertiesFormatException {
        char quote = at < text.length() ? text.charAt(at) : 0;
        int close = quote == '"' || quote == '\'' ? text.indexOf(quote, at + 1) : -1;
        if (close < 0) {
            throw invalid("");
        }
        String read = text.substring(at + 1, close);
        at = close + 1;
        return read;
    }

    // a name's characters: ASCII letters and digits, '.', '-', '_', ':' and every
    // character past ASCII
    private String name() throws InvalidPropertiesFormatException {
        int start = at;
        while (at < text.length()) {
            char c = text.charAt(at);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':' || c > 0x7f;
            if (!letter && (at == start || !((c >= '0' && c <= '9') || c == '.' || c == '-'))) {
                break;
            }
            at++;
        }
        if (at == start) {
            throw invalid("");
        }
        return text.substring(start, at);
    }

    // an element whose '<' is read: its start tag, what the DTD allows of it, its
    // content and its end tag
    private void element(boolean root) throws InvalidPropertiesFormatException {
        String name = name();
        String keyed = null;
        boolean empty;
        while (true) {
            spaces();
            if (text.startsWith("/>", at)) {
                at += 2;
                empty = true;
                break;
            }
            if (text.startsWith(">", at)) {
                at++;
                empty = false;
                break;
            }
            String attribute = name();
            spaces();
            if (!text.startsWith("=", at)) {
                throw invalid("");
            }
            at++;
            spaces();
            String value = attributeValue();
            if (attribute.equals("key") && keyed == null) {
                keyed = value;
            }
        }
        if (root) {
            if (!name.equals("properties")) {
                throw invalid("Document root element \"" + name + "\", must match DOCTYPE root \"properties\"");
            }
        }
        else if (name.equals("entry")) {
            if (keyed == null) {
                throw invalid("Attribute \"key\" is required and must be specified for element type \"entry\"");
            }
            key = keyed;
            value = new StringBuilder();
        }
        else if (name.equals("comment")) {
            if (++comments > 1) {
                throw invalid("Only one comment element may be allowed. The content of element type \"properties\""
                        + " must match \"(comment?,entry*)\"");
            }
        }
        else {
            throw invalid("Element type \"" + name + "\" must be declared.");
        }
        if (!empty) {
            content(name);
        }
        if (name.equals("entry")) {
            into.put(key, value.toString());
        }
    }

    // what an element holds up to its end tag; its text is an entry's value
    private void content(String name) throws InvalidPropertiesFormatException {
        while (true) {
            if (at >= text.length()) {
                throw invalid("");
            }
            char c = text.charAt(at);
            if (c == '&') {
                String expanded = reference(false);
                append(expanded);
            }
            else if (c != '<') {
                append(String.valueOf(c));
                at++;
            }
            else if (text.startsWith("</", at)) {
                at += 2;
                if (!name().equals(name)) {
                    throw invalid("");
                }
                spaces();
                if (!text.startsWith(">", at)) {
                    throw invalid("");
                }
                at++;
                return;
            }
            else if (text.startsWith("<![CDATA[", at)) {
                int end = text.indexOf("]]>", at);
                if (end < 0) {
                    throw invalid("");
                }
                append(text.substring(at + "<![CDATA[".length(), end));
                at = end + 3;
            }
            else if (!skipped()) {
                at++;
                element(false);
            }
        }
    }

    private void append(String read) {
        if (value != null) {
            value.append(read);
        }
    }

    // a quoted value: its references expanded, a space for each line end and tab
    private String attributeValue() throws InvalidPropertiesFormatException {
        char quote = at < text.length() ? text.charAt(at) : 0;
        if (quote != '"' && quote != '\'') {
            throw invalid("");
        }
        at++;
        StringBuilder read = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw invalid("");
            }
            char c = text.charAt(at);
            if (c == quote) {
                at++;
                return read.toString();
            }
            if (c == '<') {
                throw invalid("");
            }
            if (c == '&') {
                read.append(reference(true));
            }
            else {
                read.append(c == '\n' || c == '\t' ? ' ' : c);
                at++;
            }
        }
    }

    // &name; or a character's &#n; or &#xh;; a name no declaration has is nothing in
    // text and refused in a value
    private String reference(boolean attribute) throws InvalidPropertiesFormatException {
        int end = text.indexOf(';', at);
        if (end < 0) {
            throw invalid("");
        }
        String body = text.substring(at + 1, end);
        at = end + 1;
        if (body.startsWith("#")) {
            boolean hex = body.startsWith("#x");
            String digits = body.substring(hex ? 2 : 1);
            if (digits.isEmpty() || digits.length() > 8) {
                throw invalid("");
            }
            int code = 0;
            for (int i = 0; i < digits.length(); i++) {
                char c = digits.charAt(i);
                int digit = c >= '0' && c <= '9' ? c - '0'
                        : hex && c >= 'a' && c <= 'f' ? c - 'a' + 10 : hex && c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                if (digit < 0) {
                    throw invalid("");
                }
                code = code * (hex ? 16 : 10) + digit;
            }
            if (code > 0x10ffff) {
                throw new IllegalArgumentException(
                        "Not a valid Unicode code point: 0x" + Integer.toHexString(code).toUpperCase());
            }
            return Character.toString(code);
        }
        switch (body) {
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "amp":
                return "&";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            default:
                for (int i = 0; i < body.length(); i++) {
                    char c = body.charAt(i);
                    if (blank(c) || c == '<' || c == '&') {
                        throw invalid("");
                    }
                }
                if (body.isEmpty() || attribute) {
                    throw invalid("");
                }
                return "";
        }
    }
}
