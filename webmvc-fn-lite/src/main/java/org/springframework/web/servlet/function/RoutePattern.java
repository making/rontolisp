/*
 * Third-party: the syntax and the matching rules are those of the path patterns of Spring
 * Framework 7.1 (org.springframework.web.util.pattern.PathPattern, Apache-2.0), Copyright
 * 2002-present the original author or authors. Changed: written for this module, with
 * none of the original's code; a regular expression in a variable is refused.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A path pattern: segments between slashes, each a text that may hold {@code ?} (one
 * character), {@code *} (any number of them) and {@code {name}} (a path variable); at the
 * end, {@code **} for the rest of the path and {@code {*name}} for the rest as a
 * variable. A segment of the path is compared decoded, without its path parameters; the
 * case counts, and a slash at the end is a segment like any other.
 */
final class RoutePattern {

	/** The rest of the path matches. */
	private static final int REST = 1;

	/** The rest of the path matches and is a variable. */
	private static final int CAPTURED_REST = 2;

	private final String text;

	/** The segments, the first of them the empty one in front of the first slash. */
	private final List<String> segments;

	private final int tail;

	private final @Nullable String tailName;

	RoutePattern(String text) {
		this.text = text;
		List<String> segments = cut(text);
		String last = segments.get(segments.size() - 1);
		if (last.equals("**")) {
			this.tail = REST;
			this.tailName = null;
			segments.remove(segments.size() - 1);
		}
		else if (last.startsWith("{*") && last.endsWith("}")) {
			this.tail = CAPTURED_REST;
			this.tailName = last.substring(2, last.length() - 1);
			segments.remove(segments.size() - 1);
		}
		else {
			this.tail = 0;
			this.tailName = null;
		}
		for (String segment : segments) {
			if (segment.contains("**") || segment.contains("{*")) {
				throw new IllegalArgumentException("'" + text + "': ** and {*name} are the last segment of a pattern");
			}
			int open = segment.indexOf('{');
			while (open >= 0) {
				int close = segment.indexOf('}', open);
				if (close < 0) {
					throw new IllegalArgumentException("'" + text + "': a path variable is not closed");
				}
				if (segment.substring(open, close).indexOf(':') >= 0) {
					throw new IllegalArgumentException("'" + text + "': a regular expression in a path variable is "
							+ "not supported by webmvc-fn-lite");
				}
				open = segment.indexOf('{', close);
			}
		}
		this.segments = segments;
	}

	String text() {
		return this.text;
	}

	/**
	 * The variables of a path this pattern matches whole, or {@code null}. The empty
	 * pattern matches the empty path and the slash alone.
	 */
	@Nullable Map<String, String> match(String path) {
		if (this.text.isEmpty()) {
			return (path.isEmpty() || path.equals("/")) ? new LinkedHashMap<>() : null;
		}
		List<String> parts = cut(path);
		if (this.tail == 0 ? parts.size() != this.segments.size() : parts.size() < this.segments.size()) {
			return null;
		}
		Map<String, String> variables = head(parts, this.segments.size());
		if (variables != null && this.tail == CAPTURED_REST) {
			StringBuilder rest = new StringBuilder();
			for (int i = this.segments.size(); i < parts.size(); i++) {
				rest.append('/').append(value(parts.get(i)));
			}
			variables.put(this.tailName, rest.toString());
		}
		return variables;
	}

	/**
	 * The path the routes nested under this pattern are asked, of a path that starts with
	 * it -- empty, or from a slash on --, or {@code null} for a path that does not; its
	 * variables go into the map. A pattern ending with a slash is a prefix of the paths
	 * that go on after that slash, which then starts what is left.
	 */
	@Nullable String matchStart(String path, Map<String, String> into) {
		if (this.tail != 0) {
			Map<String, String> variables = match(path);
			if (variables == null) {
				return null;
			}
			into.putAll(variables);
			return "";
		}
		List<String> parts = cut(path);
		boolean slash = this.text.endsWith("/");
		int count = slash ? this.segments.size() - 1 : this.segments.size();
		if (slash ? parts.size() <= count : parts.size() < count) {
			return null;
		}
		Map<String, String> variables = head(parts, count);
		if (variables == null) {
			return null;
		}
		into.putAll(variables);
		// a slash the pattern ended at, or matched an empty segment after, starts the
		// rest
		int from = (!slash && count > 1 && parts.get(count - 1).isEmpty()) ? count - 1 : count;
		StringBuilder rest = new StringBuilder();
		for (int i = from; i < parts.size(); i++) {
			rest.append('/').append(parts.get(i));
		}
		return rest.toString();
	}

	/**
	 * The variables of the path's first segments against as many of this pattern's, or
	 * {@code null}. A segment with a wildcard or a variable matches no empty segment but
	 * the path's last, the one after a slash at its end.
	 */
	private @Nullable Map<String, String> head(List<String> parts, int count) {
		Map<String, String> variables = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			String segment = this.segments.get(i);
			String part = value(parts.get(i));
			if (segment.indexOf('{') < 0 && segment.indexOf('*') < 0 && segment.indexOf('?') < 0) {
				if (!segment.equals(part)) {
					return null;
				}
				continue;
			}
			if (part.isEmpty() && i < parts.size() - 1) {
				return null;
			}
			if (!segment(segment, 0, part, 0, variables)) {
				return null;
			}
		}
		return variables;
	}

	/**
	 * Whether a segment of the pattern from a position matches a segment of the path from
	 * a position: a variable takes as much as lets the rest match, and at least one
	 * character when it is the whole segment.
	 */
	private static boolean segment(String pattern, int p, String part, int at, Map<String, String> variables) {
		while (p < pattern.length()) {
			char c = pattern.charAt(p);
			if (c == '{') {
				int close = pattern.indexOf('}', p);
				String name = pattern.substring(p + 1, close);
				boolean whole = p == 0 && close == pattern.length() - 1;
				for (int end = part.length(); end >= at; end--) {
					if ((!whole || end > at) && segment(pattern, close + 1, part, end, variables)) {
						variables.put(name, part.substring(at, end));
						return true;
					}
				}
				return false;
			}
			if (c == '*') {
				for (int end = part.length(); end >= at; end--) {
					if (segment(pattern, p + 1, part, end, variables)) {
						return true;
					}
				}
				return false;
			}
			if (at == part.length() || (c != '?' && c != part.charAt(at))) {
				return false;
			}
			p++;
			at++;
		}
		return at == part.length();
	}

	/** The segments of a path: what stands between its slashes, the empty ones too. */
	private static List<String> cut(String path) {
		List<String> parts = new ArrayList<>();
		int start = 0;
		while (true) {
			int slash = path.indexOf('/', start);
			if (slash < 0) {
				parts.add(path.substring(start));
				return parts;
			}
			parts.add(path.substring(start, slash));
			start = slash + 1;
		}
	}

	/** What a segment of a path is compared as: decoded, without its path parameters. */
	private static String value(String part) {
		int parameters = part.indexOf(';');
		String name = (parameters >= 0) ? part.substring(0, parameters) : part;
		if (name.indexOf('%') < 0) {
			return name;
		}
		byte[] octets = new byte[name.length()];
		int count = 0;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '%' && i + 2 < name.length() && Character.digit(name.charAt(i + 1), 16) >= 0
					&& Character.digit(name.charAt(i + 2), 16) >= 0) {
				octets[count++] = (byte) (Character.digit(name.charAt(i + 1), 16) * 16
						+ Character.digit(name.charAt(i + 2), 16));
				i += 2;
			}
			else if (c < 0x80) {
				octets[count++] = (byte) c;
			}
			else {
				// a path is ASCII; what is not stays as it is
				return name;
			}
		}
		return new String(octets, 0, count, StandardCharsets.UTF_8);
	}

}
