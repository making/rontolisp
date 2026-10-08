package am.ik.maven;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The XML reader for POMs and {@code settings.xml}, written to accept and reject what
 * Maven's own reader (plexus-utils' MXParser, non-validating, not namespace aware) does,
 * rather than what a conforming XML parser does. The differences that matter:
 * <ul>
 * <li>the XHTML named references ({@code &copy;}, {@code &nbsp;}, ...) resolve
 * ({@link XhtmlEntities});</li>
 * <li>a DOCTYPE is skipped, its internal subset included, and an entity it declares does
 * not resolve -- so no external entity is ever fetched;</li>
 * <li>whitespace before the XML declaration is accepted;</li>
 * <li>nothing after the root element is read.</li>
 * </ul>
 * The encoding comes from a byte order mark, else from the XML declaration, else UTF-8;
 * malformed bytes decode to U+FFFD, as Maven's reader does. Line ends are normalized to
 * {@code \n}.
 */
final class XmlParser {

	/** The document is not well-formed as far as Maven's reader is concerned. */
	static final class Malformed extends Exception {

		private static final long serialVersionUID = 1L;

		Malformed(String message) {
			super(message);
		}

	}

	private static final Pattern DECLARED_ENCODING = Pattern
		.compile("encoding\\s*=\\s*([\"'])([A-Za-z][A-Za-z0-9._:-]*)\\1");

	private final String text;

	private int pos;

	/** {@link #lineOf}'s progress: the lines before index {@code countedTo}. */
	private int countedTo;

	private int countedLines = 1;

	private XmlParser(String text) {
		this.text = text;
	}

	/**
	 * Parses a document.
	 * @param bytes the document
	 * @return the root element
	 * @throws Malformed if the document is not well-formed
	 */
	static XmlElement parse(byte[] bytes) throws Malformed {
		return new XmlParser(decode(bytes)).document();
	}

	static String decode(byte[] bytes) throws Malformed {
		int offset = 0;
		Charset charset = null;
		if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
			offset = 3;
			charset = StandardCharsets.UTF_8;
		}
		else if (startsWith(bytes, 0xFE, 0xFF)) {
			offset = 2;
			charset = StandardCharsets.UTF_16BE;
		}
		else if (startsWith(bytes, 0xFF, 0xFE)) {
			offset = 2;
			charset = StandardCharsets.UTF_16LE;
		}
		else if (startsWith(bytes, 0x00, 0x3C, 0x00, 0x3F)) {
			charset = StandardCharsets.UTF_16BE;
		}
		else if (startsWith(bytes, 0x3C, 0x00, 0x3F, 0x00)) {
			charset = StandardCharsets.UTF_16LE;
		}
		if (charset == null) {
			charset = declaredEncoding(bytes);
		}
		String decoded = new String(bytes, offset, bytes.length - offset, charset);
		return decoded.indexOf('\r') < 0 ? decoded : decoded.replace("\r\n", "\n").replace('\r', '\n');
	}

	private static boolean startsWith(byte[] bytes, int... prefix) {
		if (bytes.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if ((bytes[i] & 0xFF) != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	/** The encoding an ASCII-compatible document declares, UTF-8 when none. */
	private static Charset declaredEncoding(byte[] bytes) throws Malformed {
		int start = 0;
		while (start < bytes.length && isWhitespace((char) bytes[start])) {
			start++;
		}
		int limit = Math.min(bytes.length, start + 1024);
		String head = new String(bytes, start, limit - start, StandardCharsets.ISO_8859_1);
		if (!head.startsWith("<?xml")) {
			return StandardCharsets.UTF_8;
		}
		int end = head.indexOf("?>");
		Matcher declared = DECLARED_ENCODING.matcher(end < 0 ? head : head.substring(0, end));
		if (!declared.find()) {
			return StandardCharsets.UTF_8;
		}
		String name = declared.group(2);
		try {
			return Charset.forName(name);
		}
		catch (IllegalArgumentException ex) {
			throw new Malformed("unsupported encoding '" + name + "'");
		}
	}

	private XmlElement document() throws Malformed {
		skipWhitespace();
		if (this.text.startsWith("<?xml", this.pos) && this.pos + 5 < this.text.length()
				&& isWhitespace(this.text.charAt(this.pos + 5))) {
			int end = this.text.indexOf("?>", this.pos);
			if (end < 0) {
				throw malformed("XML declaration not closed");
			}
			this.pos = end + 2;
		}
		while (true) {
			skipWhitespace();
			if (this.pos >= this.text.length()) {
				throw malformed("no root element");
			}
			if (this.text.startsWith("<!--", this.pos)) {
				comment();
			}
			else if (this.text.startsWith("<!DOCTYPE", this.pos)) {
				doctype();
			}
			else if (this.text.startsWith("<?", this.pos)) {
				processingInstruction();
			}
			else if (this.text.charAt(this.pos) == '<') {
				return elementTree();
			}
			else {
				throw malformed("only whitespace content allowed before start tag and not '"
						+ this.text.charAt(this.pos) + "'");
			}
		}
	}

	/** An element whose end tag has not been read yet. */
	private static final class Open {

		final String name;

		final int line;

		final StringBuilder text = new StringBuilder();

		final Map<String, String> attributes = new LinkedHashMap<>();

		final List<XmlElement> children = new ArrayList<>();

		boolean empty;

		int textAt = -1;

		Open(String name, int line) {
			this.name = name;
			this.line = line;
		}

		XmlElement close() {
			return new XmlElement(this.name, this.text.toString(), this.attributes, this.children, this.empty,
					this.line, this.textAt);
		}

		/** Records where the data appended since {@code from} holds non-whitespace. */
		void noteText(int from) {
			if (this.textAt >= 0) {
				return;
			}
			for (int i = from; i < this.text.length(); i++) {
				if (!isWhitespace(this.text.charAt(i))) {
					this.textAt = this.children.size();
					return;
				}
			}
		}

	}

	/** The root element and everything in it, iteratively: nesting costs no stack. */
	private XmlElement elementTree() throws Malformed {
		Deque<Open> open = new ArrayDeque<>();
		Open root = startTag();
		if (root.empty) {
			return root.close();
		}
		open.push(root);
		while (true) {
			Open top = open.peek();
			if (top == null) {
				throw new IllegalStateException("no open element");
			}
			if (this.pos >= this.text.length()) {
				throw malformed("no more data available - expected end tag </" + top.name + "> to close start tag <"
						+ top.name + "> from line " + top.line);
			}
			char c = this.text.charAt(this.pos);
			if (c == '&') {
				int from = top.text.length();
				reference(top.text);
				top.noteText(from);
			}
			else if (c != '<') {
				int end = this.pos;
				while (end < this.text.length() && this.text.charAt(end) != '<' && this.text.charAt(end) != '&') {
					end++;
				}
				int from = top.text.length();
				top.text.append(this.text, this.pos, end);
				top.noteText(from);
				this.pos = end;
			}
			else if (this.text.startsWith("</", this.pos)) {
				endTag(top);
				open.pop();
				XmlElement closed = top.close();
				Open parent = open.peek();
				if (parent == null) {
					return closed;
				}
				parent.children.add(closed);
			}
			else if (this.text.startsWith("<!--", this.pos)) {
				comment();
			}
			else if (this.text.startsWith("<![CDATA[", this.pos)) {
				int end = this.text.indexOf("]]>", this.pos + 9);
				if (end < 0) {
					throw malformed("CDATA section not terminated");
				}
				int from = top.text.length();
				top.text.append(this.text, this.pos + 9, end);
				top.noteText(from);
				this.pos = end + 3;
			}
			else if (this.text.startsWith("<?", this.pos)) {
				processingInstruction();
			}
			else if (this.text.startsWith("<!", this.pos)) {
				throw malformed("unexpected markup <!" + snippet(this.pos + 2));
			}
			else {
				Open child = startTag();
				if (child.empty) {
					top.children.add(child.close());
				}
				else {
					open.push(child);
				}
			}
		}
	}

	private Open startTag() throws Malformed {
		int line = lineOf(this.pos);
		this.pos++;
		String name = name("start tag name");
		Open element = new Open(name, line);
		while (true) {
			boolean spaced = skipWhitespace();
			if (this.pos >= this.text.length()) {
				throw malformed("start tag <" + name + "> not closed");
			}
			if (this.text.charAt(this.pos) == '>') {
				this.pos++;
				return element;
			}
			if (this.text.startsWith("/>", this.pos)) {
				this.pos += 2;
				element.empty = true;
				return element;
			}
			if (!spaced) {
				throw malformed("attribute in <" + name + "> must be preceded by whitespace");
			}
			String attribute = name("attribute name");
			if (element.attributes.containsKey(attribute)) {
				throw malformed("duplicated attribute " + attribute + " in <" + name + ">");
			}
			skipWhitespace();
			expect('=', "after attribute name " + attribute);
			skipWhitespace();
			if (this.pos >= this.text.length()
					|| (this.text.charAt(this.pos) != '"' && this.text.charAt(this.pos) != '\'')) {
				throw malformed("attribute value of " + attribute + " must be quoted");
			}
			char quote = this.text.charAt(this.pos++);
			StringBuilder value = new StringBuilder();
			while (true) {
				if (this.pos >= this.text.length()) {
					throw malformed("attribute value of " + attribute + " not terminated");
				}
				char c = this.text.charAt(this.pos);
				if (c == quote) {
					this.pos++;
					element.attributes.put(attribute, value.toString());
					break;
				}
				if (c == '<') {
					throw malformed("attribute value of " + attribute + " must not contain '<'");
				}
				if (c == '&') {
					reference(value);
				}
				else {
					value.append(c);
					this.pos++;
				}
			}
		}
	}

	private void endTag(Open top) throws Malformed {
		this.pos += 2;
		String name = name("end tag name");
		skipWhitespace();
		expect('>', "to close end tag </" + name);
		if (!name.equals(top.name)) {
			throw malformed(
					"end tag name </" + name + "> must match start tag name <" + top.name + "> from line " + top.line);
		}
	}

	private void comment() throws Malformed {
		int dashes = this.text.indexOf("--", this.pos + 4);
		if (dashes < 0 || dashes + 2 >= this.text.length()) {
			throw malformed("comment not terminated");
		}
		if (this.text.charAt(dashes + 2) != '>') {
			this.pos = dashes;
			throw malformed("in comment after two dashes (--) next character must be > not '"
					+ this.text.charAt(dashes + 2) + "'");
		}
		this.pos = dashes + 3;
	}

	private void processingInstruction() throws Malformed {
		this.pos += 2;
		String target = name("processing instruction target");
		if (target.equalsIgnoreCase("xml")) {
			throw malformed("processing instruction can not have PITarget with reserved xml name");
		}
		int end = this.text.indexOf("?>", this.pos);
		if (end < 0) {
			throw malformed("processing instruction not terminated");
		}
		this.pos = end + 2;
	}

	/** Skips a DOCTYPE, its internal subset included; nothing it declares is used. */
	private void doctype() throws Malformed {
		this.pos += 9;
		int depth = 0;
		char quote = 0;
		while (this.pos < this.text.length()) {
			char c = this.text.charAt(this.pos++);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
			}
			else if (c == '"' || c == '\'') {
				quote = c;
			}
			else if (c == '[') {
				depth++;
			}
			else if (c == ']') {
				depth--;
			}
			else if (c == '>' && depth <= 0) {
				return;
			}
			else if (c == '<' && depth > 0 && this.text.startsWith("!--", this.pos)) {
				int end = this.text.indexOf("-->", this.pos + 3);
				if (end < 0) {
					break;
				}
				this.pos = end + 3;
			}
		}
		throw malformed("DOCTYPE not terminated");
	}

	/** Reads a reference at {@code &} into {@code out}. */
	private void reference(StringBuilder out) throws Malformed {
		int start = this.pos;
		this.pos++;
		if (this.pos < this.text.length() && this.text.charAt(this.pos) == '#') {
			this.pos++;
			int radix = 10;
			if (this.pos < this.text.length() && this.text.charAt(this.pos) == 'x') {
				radix = 16;
				this.pos++;
			}
			int digits = this.pos;
			while (this.pos < this.text.length() && Character.digit(this.text.charAt(this.pos), radix) >= 0) {
				this.pos++;
			}
			if (digits == this.pos || this.pos >= this.text.length() || this.text.charAt(this.pos) != ';') {
				this.pos = start;
				throw malformed("character reference must be digits followed by ';'");
			}
			int codePoint;
			try {
				codePoint = Integer.parseInt(this.text.substring(digits, this.pos), radix);
			}
			catch (NumberFormatException ex) {
				codePoint = -1;
			}
			if (!Character.isValidCodePoint(codePoint)) {
				this.pos = start;
				throw malformed("character reference is not a valid code point");
			}
			this.pos++;
			out.appendCodePoint(codePoint);
			return;
		}
		if (this.pos >= this.text.length() || !isNameStart(this.text.charAt(this.pos))) {
			char next = this.pos < this.text.length() ? this.text.charAt(this.pos) : ' ';
			this.pos = start;
			throw malformed("entity reference names can not start with character '" + next + "'");
		}
		int nameStart = this.pos;
		while (this.pos < this.text.length() && isNameChar(this.text.charAt(this.pos))) {
			this.pos++;
		}
		if (this.pos >= this.text.length() || this.text.charAt(this.pos) != ';') {
			this.pos = start;
			throw malformed("entity reference must end with ';'");
		}
		String name = this.text.substring(nameStart, this.pos);
		this.pos++;
		String predefined = switch (name) {
			case "amp" -> "&";
			case "lt" -> "<";
			case "gt" -> ">";
			case "quot" -> "\"";
			case "apos" -> "'";
			default -> null;
		};
		if (predefined != null) {
			out.append(predefined);
			return;
		}
		Integer codePoint = XhtmlEntities.codePoint(name);
		if (codePoint == null) {
			this.pos = start;
			throw malformed("could not resolve entity named '" + name + "'");
		}
		out.appendCodePoint(codePoint);
	}

	private String name(String what) throws Malformed {
		int start = this.pos;
		if (start >= this.text.length() || !isNameStart(this.text.charAt(start))) {
			throw malformed(what + " can not start with '"
					+ (start < this.text.length() ? this.text.charAt(start) : ' ') + "'");
		}
		this.pos++;
		while (this.pos < this.text.length() && isNameChar(this.text.charAt(this.pos))) {
			this.pos++;
		}
		return this.text.substring(start, this.pos);
	}

	private void expect(char expected, String where) throws Malformed {
		if (this.pos >= this.text.length() || this.text.charAt(this.pos) != expected) {
			throw malformed("expected '" + expected + "' " + where);
		}
		this.pos++;
	}

	private boolean skipWhitespace() {
		int start = this.pos;
		while (this.pos < this.text.length() && isWhitespace(this.text.charAt(this.pos))) {
			this.pos++;
		}
		return this.pos > start;
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r';
	}

	private static boolean isNameStart(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':' || c >= 0x80;
	}

	private static boolean isNameChar(char c) {
		return isNameStart(c) || (c >= '0' && c <= '9') || c == '-' || c == '.';
	}

	private String snippet(int from) {
		return this.text.substring(from, Math.min(this.text.length(), from + 10));
	}

	/**
	 * The 1-based line of an index. Lines are counted forward from the last index asked
	 * about, so numbering every start tag of a document costs one pass over it.
	 */
	private int lineOf(int index) {
		int target = Math.min(index, this.text.length());
		if (target < this.countedTo) {
			this.countedTo = 0;
			this.countedLines = 1;
		}
		for (int i = this.countedTo; i < target; i++) {
			if (this.text.charAt(i) == '\n') {
				this.countedLines++;
			}
		}
		this.countedTo = target;
		return this.countedLines;
	}

	private Malformed malformed(String message) {
		int index = Math.min(this.pos, this.text.length());
		int lineStart = index == 0 ? 0 : this.text.lastIndexOf('\n', index - 1) + 1;
		return new Malformed(message + " (line " + lineOf(index) + ", column " + (index - lineStart + 1) + ")");
	}

}
