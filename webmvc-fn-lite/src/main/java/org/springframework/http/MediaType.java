/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A media type: a type, a subtype and parameters. The type, the subtype and the names of
 * the parameters are kept in lower case; a parameter's value is kept as written.
 */
public class MediaType implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String ALL_VALUE = "*/*";

	public static final String APPLICATION_FORM_URLENCODED_VALUE = "application/x-www-form-urlencoded";

	public static final String APPLICATION_JSON_VALUE = "application/json";

	public static final String APPLICATION_OCTET_STREAM_VALUE = "application/octet-stream";

	public static final String APPLICATION_PROBLEM_JSON_VALUE = "application/problem+json";

	public static final String APPLICATION_XML_VALUE = "application/xml";

	public static final String MULTIPART_FORM_DATA_VALUE = "multipart/form-data";

	public static final String TEXT_EVENT_STREAM_VALUE = "text/event-stream";

	public static final String TEXT_HTML_VALUE = "text/html";

	public static final String TEXT_MARKDOWN_VALUE = "text/markdown";

	public static final String TEXT_PLAIN_VALUE = "text/plain";

	public static final String TEXT_XML_VALUE = "text/xml";

	public static final MediaType ALL = new MediaType("*", "*", (Void) null);

	public static final MediaType APPLICATION_FORM_URLENCODED = new MediaType("application", "x-www-form-urlencoded",
			(Void) null);

	public static final MediaType APPLICATION_JSON = new MediaType("application", "json", (Void) null);

	public static final MediaType APPLICATION_OCTET_STREAM = new MediaType("application", "octet-stream", (Void) null);

	public static final MediaType APPLICATION_PROBLEM_JSON = new MediaType("application", "problem+json", (Void) null);

	public static final MediaType APPLICATION_XML = new MediaType("application", "xml", (Void) null);

	public static final MediaType MULTIPART_FORM_DATA = new MediaType("multipart", "form-data", (Void) null);

	public static final MediaType TEXT_EVENT_STREAM = new MediaType("text", "event-stream", (Void) null);

	public static final MediaType TEXT_HTML = new MediaType("text", "html", (Void) null);

	public static final MediaType TEXT_MARKDOWN = new MediaType("text", "markdown", (Void) null);

	public static final MediaType TEXT_PLAIN = new MediaType("text", "plain", (Void) null);

	public static final MediaType TEXT_XML = new MediaType("text", "xml", (Void) null);

	private static final String CHARSET = "charset";

	private static final String QUALITY = "q";

	private final String type;

	private final String subtype;

	/**
	 * The parameters, {@code null} for none; of its exact class, as the maps of
	 * {@code HttpHeaders} are.
	 */
	private final @Nullable LinkedHashMap<String, String> parameters;

	/**
	 * A constant of this class: lower case already, and no parameter. Nothing here can
	 * throw and nothing is called, so that a program compiled from source makes the
	 * constants before it starts and tests for them nowhere (.kb/webmvc-fn-lite.md,
	 * "Written for the compiler").
	 */
	private MediaType(String type, String subtype, Void constant) {
		this.type = type;
		this.subtype = subtype;
		this.parameters = null;
	}

	public MediaType(String type) {
		this(type, "*");
	}

	public MediaType(String type, String subtype) {
		this(type, subtype, (Map<String, String>) null);
	}

	public MediaType(String type, String subtype, Charset charset) {
		this(type, subtype, with(null, CHARSET, charset.name()));
	}

	public MediaType(MediaType other, Charset charset) {
		this(other.type, other.subtype, with(other.parameters, CHARSET, charset.name()));
	}

	public MediaType(MediaType other, @Nullable Map<String, String> parameters) {
		this(other.type, other.subtype, parameters);
	}

	public MediaType(String type, String subtype, @Nullable Map<String, String> parameters) {
		if (type == null || type.isEmpty() || subtype == null || subtype.isEmpty()) {
			throw new IllegalArgumentException("'type' and 'subtype' must not be empty");
		}
		this.type = lowerCase(type);
		this.subtype = lowerCase(subtype);
		LinkedHashMap<String, String> kept = null;
		if (parameters != null && !parameters.isEmpty()) {
			kept = new LinkedHashMap<>();
			for (Map.Entry<String, String> parameter : parameters.entrySet()) {
				kept.put(lowerCase(parameter.getKey()), parameter.getValue());
			}
		}
		this.parameters = kept;
	}

	private static Map<String, String> with(@Nullable Map<String, String> parameters, String name, String value) {
		Map<String, String> all = (parameters != null) ? new LinkedHashMap<>(parameters) : new LinkedHashMap<>();
		all.put(name, value);
		return all;
	}

	/** A text in lower case: media types and the names of header fields are ASCII. */
	static String lowerCase(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= 'A' && c <= 'Z') {
				StringBuilder lower = new StringBuilder(text.length());
				for (int j = 0; j < text.length(); j++) {
					char d = text.charAt(j);
					lower.append((d >= 'A' && d <= 'Z') ? (char) (d + 32) : d);
				}
				return lower.toString();
			}
		}
		return text;
	}

	public String getType() {
		return this.type;
	}

	public String getSubtype() {
		return this.subtype;
	}

	/** The part of the subtype after its last {@code +}, or {@code null}. */
	public @Nullable String getSubtypeSuffix() {
		int plus = this.subtype.lastIndexOf('+');
		return (plus >= 0 && plus + 1 < this.subtype.length()) ? this.subtype.substring(plus + 1) : null;
	}

	/** The charset the {@code charset} parameter names, or {@code null}. */
	public @Nullable Charset getCharset() {
		String charset = getParameter(CHARSET);
		return (charset != null) ? Charset.forName(unquote(charset)) : null;
	}

	public @Nullable String getParameter(String name) {
		return (this.parameters != null) ? this.parameters.get(lowerCase(name)) : null;
	}

	public Map<String, String> getParameters() {
		return Collections.unmodifiableMap((this.parameters != null) ? this.parameters : new LinkedHashMap<>());
	}

	/** The value of the {@code q} parameter, 1.0 without one. */
	public double getQualityValue() {
		String quality = getParameter(QUALITY);
		return (quality != null) ? Double.parseDouble(unquote(quality)) : 1.0;
	}

	public boolean isWildcardType() {
		return "*".equals(this.type);
	}

	public boolean isWildcardSubtype() {
		return "*".equals(this.subtype) || this.subtype.startsWith("*+");
	}

	public boolean isConcrete() {
		return !isWildcardType() && !isWildcardSubtype();
	}

	/**
	 * Whether this type includes the other: {@code text/*} includes {@code text/plain},
	 * and {@code application/*+json} includes {@code application/problem+json}. Not
	 * symmetric.
	 */
	public boolean includes(@Nullable MediaType other) {
		if (other == null) {
			return false;
		}
		if (isWildcardType()) {
			return true;
		}
		if (!this.type.equals(other.type)) {
			return false;
		}
		if (this.subtype.equals(other.subtype)) {
			return true;
		}
		if (isWildcardSubtype()) {
			int plus = this.subtype.lastIndexOf('+');
			if (plus < 0) {
				return true;
			}
			int otherPlus = other.subtype.lastIndexOf('+');
			return otherPlus >= 0 && this.subtype.substring(plus + 1).equals(other.subtype.substring(otherPlus + 1))
					&& "*".equals(this.subtype.substring(0, plus));
		}
		return false;
	}

	/** Whether one of the two types includes the other. Symmetric. */
	public boolean isCompatibleWith(@Nullable MediaType other) {
		if (other == null) {
			return false;
		}
		if (isWildcardType() || other.isWildcardType()) {
			return true;
		}
		if (!this.type.equals(other.type)) {
			return false;
		}
		if (this.subtype.equals(other.subtype)) {
			return true;
		}
		if (isWildcardSubtype() || other.isWildcardSubtype()) {
			String suffix = getSubtypeSuffix();
			String otherSuffix = other.getSubtypeSuffix();
			if ("*".equals(this.subtype) || "*".equals(other.subtype)) {
				return true;
			}
			if (isWildcardSubtype() && suffix != null) {
				return suffix.equals(other.subtype) || suffix.equals(otherSuffix);
			}
			if (other.isWildcardSubtype() && otherSuffix != null) {
				return this.subtype.equals(otherSuffix) || otherSuffix.equals(suffix);
			}
		}
		return false;
	}

	public boolean equalsTypeAndSubtype(@Nullable MediaType other) {
		return other != null && this.type.equals(other.type) && this.subtype.equals(other.subtype);
	}

	/** This type without its {@code q} parameter. */
	public MediaType removeQualityValue() {
		if (this.parameters == null || !this.parameters.containsKey(QUALITY)) {
			return this;
		}
		Map<String, String> kept = new LinkedHashMap<>(this.parameters);
		kept.remove(QUALITY);
		return new MediaType(this.type, this.subtype, kept);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof MediaType that) || !this.type.equals(that.type) || !this.subtype.equals(that.subtype)) {
			return false;
		}
		if (this.parameters == null || that.parameters == null) {
			return this.parameters == that.parameters;
		}
		if (this.parameters.size() != that.parameters.size()) {
			return false;
		}
		for (Map.Entry<String, String> parameter : this.parameters.entrySet()) {
			String value = that.parameters.get(parameter.getKey());
			if (value == null) {
				return false;
			}
			// a charset is the same under any of its names and in either case
			if (CHARSET.equals(parameter.getKey()) ? !Objects.equals(getCharset(), that.getCharset())
					: !parameter.getValue().equals(value)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = this.type.hashCode() * 31 + this.subtype.hashCode();
		if (this.parameters != null) {
			for (String name : this.parameters.keySet()) {
				hash += name.hashCode();
			}
		}
		return hash;
	}

	@Override
	public String toString() {
		if (this.parameters == null) {
			return this.type + "/" + this.subtype;
		}
		StringBuilder text = new StringBuilder(this.type).append('/').append(this.subtype);
		for (Map.Entry<String, String> parameter : this.parameters.entrySet()) {
			text.append(';').append(parameter.getKey()).append('=').append(parameter.getValue());
		}
		return text.toString();
	}

	public static MediaType valueOf(String value) {
		return parseMediaType(value);
	}

	/**
	 * Reads one media type, or {@link InvalidMediaTypeException} for a text that is none.
	 */
	public static MediaType parseMediaType(String mediaType) {
		if (mediaType == null || mediaType.isEmpty()) {
			throw new InvalidMediaTypeException(mediaType, "'mediaType' must not be empty");
		}
		List<String> parts = split(mediaType, ';');
		String full = parts.get(0).strip();
		if (full.isEmpty()) {
			throw new InvalidMediaTypeException(mediaType, "'mediaType' must not be empty");
		}
		if ("*".equals(full)) {
			full = "*/*";
		}
		int slash = full.indexOf('/');
		if (slash < 0) {
			throw new InvalidMediaTypeException(mediaType, "does not contain '/'");
		}
		if (slash == full.length() - 1) {
			throw new InvalidMediaTypeException(mediaType, "does not contain subtype after '/'");
		}
		String type = full.substring(0, slash);
		String subtype = full.substring(slash + 1);
		if ("*".equals(type) && !"*".equals(subtype)) {
			throw new InvalidMediaTypeException(mediaType, "wildcard type is legal only in '*/*' (all mime types)");
		}
		Map<String, String> parameters = new LinkedHashMap<>();
		for (int i = 1; i < parts.size(); i++) {
			String parameter = parts.get(i).strip();
			int equals = parameter.indexOf('=');
			if (equals >= 0) {
				parameters.put(parameter.substring(0, equals).strip(), parameter.substring(equals + 1).strip());
			}
		}
		try {
			return new MediaType(type, subtype, parameters);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidMediaTypeException(mediaType, ex.getMessage());
		}
	}

	/** Reads a list of media types, as an {@code Accept} field holds one. */
	public static List<MediaType> parseMediaTypes(@Nullable String mediaTypes) {
		if (mediaTypes == null || mediaTypes.isEmpty()) {
			return Collections.emptyList();
		}
		List<MediaType> all = new ArrayList<>();
		for (String part : split(mediaTypes, ',')) {
			if (!part.isBlank()) {
				all.add(parseMediaType(part));
			}
		}
		return all;
	}

	public static List<MediaType> parseMediaTypes(@Nullable List<String> mediaTypes) {
		if (mediaTypes == null || mediaTypes.isEmpty()) {
			return Collections.emptyList();
		}
		List<MediaType> all = new ArrayList<>();
		for (String mediaType : mediaTypes) {
			all.addAll(parseMediaTypes(mediaType));
		}
		return all;
	}

	/** The types as an {@code Accept} field holds them. */
	public static String toString(Collection<MediaType> mediaTypes) {
		StringBuilder text = new StringBuilder();
		for (MediaType mediaType : mediaTypes) {
			if (text.length() > 0) {
				text.append(", ");
			}
			text.append(mediaType);
		}
		return text.toString();
	}

	/** The parts of a text between a separator, a separator inside quotes kept. */
	private static List<String> split(String text, char separator) {
		List<String> parts = new ArrayList<>();
		boolean quoted = false;
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			}
			else if (c == '\\' && quoted) {
				i++;
			}
			else if (c == separator && !quoted) {
				parts.add(text.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(text.substring(start));
		return parts;
	}

	private static String unquote(String value) {
		return (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
				? value.substring(1, value.length() - 1) : value;
	}

}
