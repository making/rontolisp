/*
 * Third-party: this class has the name of a class of Spring Framework 7.1 (Apache-2.0),
 * Copyright 2002-present the original author or authors, and stands for it where there
 * is no Jackson. Changed: it is not the original and has none of its code; it reads and
 * writes the JSON values alone.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http.converter.json;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

/**
 * The JSON converter of an application that names Spring Framework's, without Jackson: it
 * writes and reads the values JSON has -- a {@code Map} as an object, a
 * {@code Collection} or an array of objects as an array, a {@code String}, a
 * {@code Number}, a {@code Boolean}, {@code null}, an enum constant as its name -- as
 * Jackson writes and reads them with no configuration. An object of any other class is
 * refused, by its name: mapping one needs reflection, which is what this module is for
 * the lack of. A body is read into a {@code LinkedHashMap}, an {@code ArrayList}, a
 * {@code String}, an {@code Integer}, a {@code Long}, a {@code Double} or a
 * {@code Boolean}; an integer past a {@code long} is refused, where Jackson reads a
 * {@code BigInteger}.
 */
public class JacksonJsonHttpMessageConverter extends AbstractHttpMessageConverter<Object> {

	public JacksonJsonHttpMessageConverter() {
		super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
	}

	@Override
	protected boolean supports(Class<?> clazz) {
		return true;
	}

	@Override
	// A document of JSON's null is read as null, as Jackson reads it: the signature is
	// Spring's.
	@SuppressWarnings("NullAway")
	protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
		MediaType contentType = inputMessage.getHeaders().getContentType();
		Charset charset = (contentType != null) ? contentType.getCharset() : null;
		String text = new String(inputMessage.getBody().readAllBytes(),
				(charset != null) ? charset : StandardCharsets.UTF_8);
		@Nullable Object value;
		try {
			value = new JsonReader(text).document();
		}
		catch (IllegalArgumentException ex) {
			throw new HttpMessageNotReadableException("JSON parse error: " + ex.getMessage(), ex, inputMessage);
		}
		boolean fits;
		if (clazz == Object.class) {
			fits = true;
		}
		else if (clazz == Map.class || clazz == LinkedHashMap.class) {
			fits = value instanceof Map;
		}
		else if (clazz == List.class || clazz == Collection.class || clazz == ArrayList.class) {
			fits = value instanceof List;
		}
		else if (clazz == String.class) {
			fits = value instanceof String;
		}
		else if (clazz == Boolean.class) {
			fits = value instanceof Boolean;
		}
		else if (clazz == Number.class) {
			fits = value instanceof Number;
		}
		else if (clazz == Integer.class) {
			fits = value instanceof Integer;
		}
		else if (clazz == Long.class) {
			if (value instanceof Integer small) {
				value = small.longValue();
			}
			fits = value instanceof Long;
		}
		else if (clazz == Double.class) {
			if (value instanceof Integer || value instanceof Long) {
				value = ((Number) value).doubleValue();
			}
			fits = value instanceof Double;
		}
		else {
			throw new HttpMessageConversionException(
					"webmvc-fn-lite reads JSON into a Map, a List, a String, a Number or a Boolean, not into "
							+ clazz.getName() + ": an object of a class needs reflection");
		}
		if (!fits) {
			throw new HttpMessageNotReadableException(
					"JSON parse error: the document is not what " + clazz.getName() + " is read from", inputMessage);
		}
		return value;
	}

	@Override
	protected void writeInternal(Object object, HttpOutputMessage outputMessage) throws IOException {
		StringBuilder text = new StringBuilder();
		JsonWriter.write(object, text);
		outputMessage.getBody().write(text.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Writes a value as Jackson does by default: no white space, a map's keys in its
	 * order.
	 */
	private static final class JsonWriter {

		static void write(@Nullable Object value, StringBuilder out) {
			if (value == null) {
				out.append("null");
			}
			else if (value instanceof CharSequence || value instanceof Character) {
				string(value.toString(), out);
			}
			else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
				out.append(value);
			}
			else if (value instanceof Double number) {
				// a number JSON has no text for is written as a string
				if (number.isNaN() || number.isInfinite()) {
					string(number.toString(), out);
				}
				else {
					out.append(number);
				}
			}
			else if (value instanceof Float number) {
				if (number.isNaN() || number.isInfinite()) {
					string(number.toString(), out);
				}
				else {
					out.append(number);
				}
			}
			else if (value instanceof Number) {
				out.append(value);
			}
			else if (value instanceof Map<?, ?> map) {
				out.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (entry.getKey() == null) {
						throw new HttpMessageNotWritableException("Could not write JSON: a map has the key null");
					}
					if (!first) {
						out.append(',');
					}
					first = false;
					string(entry.getKey().toString(), out);
					out.append(':');
					write(entry.getValue(), out);
				}
				out.append('}');
			}
			else if (value instanceof Iterable<?> elements) {
				out.append('[');
				boolean first = true;
				for (Object element : elements) {
					if (!first) {
						out.append(',');
					}
					first = false;
					write(element, out);
				}
				out.append(']');
			}
			else if (value instanceof Object[] elements) {
				out.append('[');
				for (int i = 0; i < elements.length; i++) {
					if (i > 0) {
						out.append(',');
					}
					write(elements[i], out);
				}
				out.append(']');
			}
			else if (value instanceof Enum<?> constant) {
				string(constant.name(), out);
			}
			else {
				throw new HttpMessageNotWritableException(
						"webmvc-fn-lite writes a Map, a Collection, a String, a Number, a Boolean or an enum constant as JSON, not "
								+ value.getClass().getName() + ": an object of a class needs reflection");
			}
		}

		private static void string(String text, StringBuilder out) {
			out.append('"');
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				switch (c) {
					case '"':
						out.append("\\\"");
						break;
					case '\\':
						out.append("\\\\");
						break;
					case '\b':
						out.append("\\b");
						break;
					case '\t':
						out.append("\\t");
						break;
					case '\n':
						out.append("\\n");
						break;
					case '\f':
						out.append("\\f");
						break;
					case '\r':
						out.append("\\r");
						break;
					default:
						if (c < 0x20) {
							out.append("\\u00").append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xF));
						}
						else {
							out.append(c);
						}
				}
			}
			out.append('"');
		}

		private static final String HEX = "0123456789ABCDEF";

	}

	/**
	 * Reads one JSON document (RFC 8259); what is no JSON is an
	 * {@link IllegalArgumentException}.
	 */
	private static final class JsonReader {

		private final String text;

		private int at;

		JsonReader(String text) {
			this.text = text;
		}

		@Nullable Object document() {
			blanks();
			if (this.at == this.text.length()) {
				throw new IllegalArgumentException("no content");
			}
			@Nullable Object value = value();
			blanks();
			if (this.at < this.text.length()) {
				throw refusal("a second value");
			}
			return value;
		}

		private @Nullable Object value() {
			if (this.at == this.text.length()) {
				throw new IllegalArgumentException("the document ends where a value is expected");
			}
			char c = this.text.charAt(this.at);
			if (c == '{') {
				return object();
			}
			if (c == '[') {
				return array();
			}
			if (c == '"') {
				return string();
			}
			if (c == '-' || (c >= '0' && c <= '9')) {
				return number();
			}
			if (word("true")) {
				return Boolean.TRUE;
			}
			if (word("false")) {
				return Boolean.FALSE;
			}
			if (word("null")) {
				return null;
			}
			throw refusal("no value");
		}

		private boolean word(String word) {
			if (this.text.startsWith(word, this.at)) {
				this.at += word.length();
				return true;
			}
			return false;
		}

		private Map<String, @Nullable Object> object() {
			Map<String, @Nullable Object> members = new LinkedHashMap<>();
			this.at++;
			blanks();
			if (peek() == '}') {
				this.at++;
				return members;
			}
			while (true) {
				blanks();
				if (peek() != '"') {
					throw refusal("no member name");
				}
				String name = string();
				blanks();
				expect(':');
				blanks();
				members.put(name, value());
				blanks();
				if (peek() == '}') {
					this.at++;
					return members;
				}
				expect(',');
			}
		}

		private List<@Nullable Object> array() {
			List<@Nullable Object> elements = new ArrayList<>();
			this.at++;
			blanks();
			if (peek() == ']') {
				this.at++;
				return elements;
			}
			while (true) {
				blanks();
				elements.add(value());
				blanks();
				if (peek() == ']') {
					this.at++;
					return elements;
				}
				expect(',');
			}
		}

		private String string() {
			StringBuilder value = new StringBuilder();
			this.at++;
			while (true) {
				if (this.at == this.text.length()) {
					throw new IllegalArgumentException("the document ends inside a string");
				}
				char c = this.text.charAt(this.at++);
				if (c == '"') {
					return value.toString();
				}
				if (c < 0x20) {
					throw new IllegalArgumentException("a control character in a string at " + (this.at - 1));
				}
				if (c != '\\') {
					value.append(c);
					continue;
				}
				char escaped = peek();
				this.at++;
				switch (escaped) {
					case '"':
					case '\\':
					case '/':
						value.append(escaped);
						break;
					case 'b':
						value.append('\b');
						break;
					case 'f':
						value.append('\f');
						break;
					case 'n':
						value.append('\n');
						break;
					case 'r':
						value.append('\r');
						break;
					case 't':
						value.append('\t');
						break;
					case 'u':
						if (this.at + 4 > this.text.length()) {
							throw new IllegalArgumentException("the document ends inside an escape");
						}
						int unit = 0;
						for (int i = 0; i < 4; i++) {
							int digit = Character.digit(this.text.charAt(this.at++), 16);
							if (digit < 0) {
								throw new IllegalArgumentException("no hexadecimal digit at " + (this.at - 1));
							}
							unit = unit * 16 + digit;
						}
						value.append((char) unit);
						break;
					default:
						throw new IllegalArgumentException("no escape at " + (this.at - 1));
				}
			}
		}

		private Number number() {
			int start = this.at;
			if (peek() == '-') {
				this.at++;
			}
			int digits = digits();
			if (digits == 0 || (digits > 1 && this.text.charAt(this.at - digits) == '0')) {
				throw new IllegalArgumentException("no number at " + start);
			}
			boolean integer = true;
			if (peek() == '.') {
				this.at++;
				integer = false;
				if (digits() == 0) {
					throw new IllegalArgumentException("no number at " + start);
				}
			}
			if (peek() == 'e' || peek() == 'E') {
				this.at++;
				integer = false;
				if (peek() == '+' || peek() == '-') {
					this.at++;
				}
				if (digits() == 0) {
					throw new IllegalArgumentException("no number at " + start);
				}
			}
			String number = this.text.substring(start, this.at);
			if (!integer) {
				return Double.valueOf(number);
			}
			// the narrower of the two that holds it
			if (number.length() <= 9) {
				return Integer.valueOf(number);
			}
			long value;
			try {
				value = Long.parseLong(number);
			}
			catch (NumberFormatException ex) {
				throw new IllegalArgumentException("an integer past a long at " + start);
			}
			if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
				return Integer.valueOf((int) value);
			}
			return Long.valueOf(value);
		}

		private int digits() {
			int start = this.at;
			while (this.at < this.text.length() && this.text.charAt(this.at) >= '0'
					&& this.text.charAt(this.at) <= '9') {
				this.at++;
			}
			return this.at - start;
		}

		private void blanks() {
			while (this.at < this.text.length()) {
				char c = this.text.charAt(this.at);
				if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
					return;
				}
				this.at++;
			}
		}

		/** The character at the position, or 0 at the end, which no value starts with. */
		private char peek() {
			return (this.at < this.text.length()) ? this.text.charAt(this.at) : 0;
		}

		private void expect(char c) {
			if (peek() != c) {
				throw refusal("no '" + c + "'");
			}
			this.at++;
		}

		private IllegalArgumentException refusal(String what) {
			return new IllegalArgumentException(what + " at " + this.at);
		}

	}

}
