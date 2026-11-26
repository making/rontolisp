/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jspecify.annotations.Nullable;

/**
 * The header fields of a request or a response: each name with its values in order, found
 * whatever the case of the name, which is kept as it was first written.
 */
public class HttpHeaders implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String ACCEPT = "Accept";

	public static final String ACCEPT_CHARSET = "Accept-Charset";

	public static final String ACCEPT_ENCODING = "Accept-Encoding";

	public static final String ACCEPT_LANGUAGE = "Accept-Language";

	public static final String ACCEPT_PATCH = "Accept-Patch";

	public static final String ACCEPT_QUERY = "Accept-Query";

	public static final String ACCEPT_RANGES = "Accept-Ranges";

	public static final String ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

	public static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";

	public static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";

	public static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";

	public static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";

	public static final String ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";

	public static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";

	public static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";

	public static final String AGE = "Age";

	public static final String ALLOW = "Allow";

	public static final String AUTHORIZATION = "Authorization";

	public static final String CACHE_CONTROL = "Cache-Control";

	public static final String CONNECTION = "Connection";

	public static final String CONTENT_ENCODING = "Content-Encoding";

	public static final String CONTENT_DISPOSITION = "Content-Disposition";

	public static final String CONTENT_LANGUAGE = "Content-Language";

	public static final String CONTENT_LENGTH = "Content-Length";

	public static final String CONTENT_LOCATION = "Content-Location";

	public static final String CONTENT_RANGE = "Content-Range";

	public static final String CONTENT_TYPE = "Content-Type";

	public static final String COOKIE = "Cookie";

	public static final String DATE = "Date";

	public static final String ETAG = "ETag";

	public static final String EXPECT = "Expect";

	public static final String EXPIRES = "Expires";

	public static final String FROM = "From";

	public static final String HOST = "Host";

	public static final String IF_MATCH = "If-Match";

	public static final String IF_MODIFIED_SINCE = "If-Modified-Since";

	public static final String IF_NONE_MATCH = "If-None-Match";

	public static final String IF_RANGE = "If-Range";

	public static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";

	public static final String LAST_MODIFIED = "Last-Modified";

	public static final String LINK = "Link";

	public static final String LOCATION = "Location";

	public static final String MAX_FORWARDS = "Max-Forwards";

	public static final String ORIGIN = "Origin";

	public static final String PRAGMA = "Pragma";

	public static final String PROXY_AUTHENTICATE = "Proxy-Authenticate";

	public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";

	public static final String RANGE = "Range";

	public static final String REFERER = "Referer";

	public static final String RETRY_AFTER = "Retry-After";

	public static final String SERVER = "Server";

	public static final String SET_COOKIE = "Set-Cookie";

	public static final String SET_COOKIE2 = "Set-Cookie2";

	public static final String TE = "TE";

	public static final String TRAILER = "Trailer";

	public static final String TRANSFER_ENCODING = "Transfer-Encoding";

	public static final String UPGRADE = "Upgrade";

	public static final String USER_AGENT = "User-Agent";

	public static final String VARY = "Vary";

	public static final String VIA = "Via";

	public static final String WARNING = "Warning";

	public static final String WWW_AUTHENTICATE = "WWW-Authenticate";

	/**
	 * The values by the name in lower case, and the name as first written. The maps are
	 * of their exact class and this class has no subclass here: a program compiled from
	 * source then calls them by name, and what a map of some other class can do is not
	 * what a header can do (.kb/webmvc-fn-lite.md, "Written for the compiler").
	 */
	private final LinkedHashMap<String, List<String>> values;

	private final LinkedHashMap<String, String> names;

	private final boolean readOnly;

	public HttpHeaders() {
		this.values = new LinkedHashMap<>();
		this.names = new LinkedHashMap<>();
		this.readOnly = false;
	}

	/** A view of the fields of another that refuses every change. */
	private HttpHeaders(HttpHeaders shared) {
		this.values = shared.values;
		this.names = shared.names;
		this.readOnly = true;
	}

	/** The values of a field, or {@code null} for a field that is not there. */
	public @Nullable List<String> get(String headerName) {
		List<String> all = this.values.get(MediaType.lowerCase(headerName));
		return (all != null && this.readOnly) ? Collections.unmodifiableList(all) : all;
	}

	public List<String> getOrEmpty(String headerName) {
		List<String> all = get(headerName);
		return (all != null) ? all : Collections.emptyList();
	}

	public @Nullable String getFirst(String headerName) {
		List<String> all = get(headerName);
		return (all != null && !all.isEmpty()) ? all.get(0) : null;
	}

	public void add(String headerName, @Nullable String headerValue) {
		if (this.readOnly) {
			throw new UnsupportedOperationException();
		}
		String key = MediaType.lowerCase(headerName);
		List<String> all = this.values.get(key);
		if (all == null) {
			all = new ArrayList<>(1);
			this.values.put(key, all);
			this.names.put(key, headerName);
		}
		all.add(headerValue);
	}

	public void addAll(String headerName, List<? extends String> headerValues) {
		for (String headerValue : headerValues) {
			add(headerName, headerValue);
		}
	}

	public void addAll(HttpHeaders headers) {
		headers.forEach((name, all) -> addAll(name, all));
	}

	public void set(String headerName, @Nullable String headerValue) {
		remove(headerName);
		add(headerName, headerValue);
	}

	public void setAll(Map<String, String> fields) {
		for (Map.Entry<String, String> field : fields.entrySet()) {
			set(field.getKey(), field.getValue());
		}
	}

	public @Nullable List<String> put(String headerName, List<String> headerValues) {
		List<String> before = remove(headerName);
		addAll(headerName, headerValues);
		return before;
	}

	public void putAll(HttpHeaders headers) {
		headers.forEach((name, all) -> put(name, all));
	}

	public @Nullable List<String> remove(String headerName) {
		if (this.readOnly) {
			throw new UnsupportedOperationException();
		}
		String key = MediaType.lowerCase(headerName);
		this.names.remove(key);
		return this.values.remove(key);
	}

	public void clear() {
		for (String headerName : headerNames()) {
			remove(headerName);
		}
	}

	public boolean containsHeader(String headerName) {
		return get(headerName) != null;
	}

	/** The names of the fields, each as it was first written. */
	public Set<String> headerNames() {
		return new LinkedHashSet<>(this.names.values());
	}

	public int size() {
		return headerNames().size();
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	public void forEach(BiConsumer<? super String, ? super List<String>> action) {
		for (String headerName : headerNames()) {
			action.accept(headerName, getOrEmpty(headerName));
		}
	}

	/** Each field with its first value. */
	public Map<String, String> toSingleValueMap() {
		Map<String, String> first = new LinkedHashMap<>();
		forEach((name, all) -> first.put(name, all.isEmpty() ? null : all.get(0)));
		return first;
	}

	public void setAccept(List<MediaType> acceptableMediaTypes) {
		set(ACCEPT, MediaType.toString(acceptableMediaTypes));
	}

	public List<MediaType> getAccept() {
		return MediaType.parseMediaTypes(get(ACCEPT));
	}

	public void setAllow(Set<HttpMethod> allowedMethods) {
		StringBuilder text = new StringBuilder();
		for (HttpMethod method : allowedMethods) {
			if (text.length() > 0) {
				text.append(',');
			}
			text.append(method.name());
		}
		set(ALLOW, text.toString());
	}

	public Set<HttpMethod> getAllow() {
		Set<HttpMethod> allowed = new LinkedHashSet<>();
		for (String name : getValuesAsList(ALLOW)) {
			allowed.add(HttpMethod.valueOf(name));
		}
		return allowed;
	}

	public void setBearerAuth(String token) {
		set(AUTHORIZATION, "Bearer " + token);
	}

	public void setCacheControl(@Nullable String cacheControl) {
		setOrRemove(CACHE_CONTROL, cacheControl);
	}

	public @Nullable String getCacheControl() {
		List<String> all = getValuesAsList(CACHE_CONTROL);
		return all.isEmpty() ? null : String.join(", ", all);
	}

	public void setContentLength(long contentLength) {
		if (contentLength < 0) {
			throw new IllegalArgumentException("Content-Length must be a non-negative number");
		}
		set(CONTENT_LENGTH, Long.toString(contentLength));
	}

	/** The length the {@code Content-Length} field tells, -1 without one. */
	public long getContentLength() {
		String length = getFirst(CONTENT_LENGTH);
		return (length != null) ? Long.parseLong(length) : -1;
	}

	public void setContentType(@Nullable MediaType mediaType) {
		if (mediaType == null) {
			remove(CONTENT_TYPE);
			return;
		}
		if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
			throw new IllegalArgumentException("Content-Type cannot contain wildcard "
					+ (mediaType.isWildcardType() ? "type" : "subtype") + " '*'");
		}
		set(CONTENT_TYPE, mediaType.toString());
	}

	/** The type the {@code Content-Type} field tells, {@code null} without one. */
	public @Nullable MediaType getContentType() {
		String type = getFirst(CONTENT_TYPE);
		return (type != null && !type.isEmpty()) ? MediaType.parseMediaType(type) : null;
	}

	public void setETag(@Nullable String tag) {
		if (tag != null) {
			if (!tag.startsWith("\"") && !tag.startsWith("W/\"")) {
				throw new IllegalArgumentException("ETag does not start with W/ or \"");
			}
			if (!tag.endsWith("\"")) {
				throw new IllegalArgumentException("ETag does not end with \"");
			}
		}
		setOrRemove(ETAG, tag);
	}

	public @Nullable String getETag() {
		return getFirst(ETAG);
	}

	public void setLocation(@Nullable URI location) {
		setOrRemove(LOCATION, (location != null) ? ascii(location.toString()) : null);
	}

	/**
	 * A URI in ASCII: what is not is written as the escaped octets of its UTF-8. It is
	 * {@code URI.toASCIIString()} without the normalization that comes first there, which
	 * a program compiled from source would carry the tables of for a field it most often
	 * writes in ASCII.
	 */
	private static String ascii(String uri) {
		int at = 0;
		while (at < uri.length() && uri.charAt(at) < 0x80) {
			at++;
		}
		if (at == uri.length()) {
			return uri;
		}
		StringBuilder text = new StringBuilder(uri.length() + 16);
		for (byte octet : uri.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
			if (octet >= 0) {
				text.append((char) octet);
			}
			else {
				text.append('%')
					.append("0123456789ABCDEF".charAt((octet >> 4) & 0xF))
					.append("0123456789ABCDEF".charAt(octet & 0xF));
			}
		}
		return text.toString();
	}

	public @Nullable URI getLocation() {
		String location = getFirst(LOCATION);
		return (location != null) ? URI.create(location) : null;
	}

	public void setOrigin(@Nullable String origin) {
		setOrRemove(ORIGIN, origin);
	}

	public @Nullable String getOrigin() {
		return getFirst(ORIGIN);
	}

	public void setVary(List<String> requestHeaders) {
		set(VARY, String.join(", ", requestHeaders));
	}

	public List<String> getVary() {
		return getValuesAsList(VARY);
	}

	/** The values of a field, each cut at its commas. */
	public List<String> getValuesAsList(String headerName) {
		List<String> parts = new ArrayList<>();
		for (String value : getOrEmpty(headerName)) {
			if (value != null) {
				int start = 0;
				while (start <= value.length()) {
					int comma = value.indexOf(',', start);
					String part = value.substring(start, (comma < 0) ? value.length() : comma).strip();
					if (!part.isEmpty()) {
						parts.add(part);
					}
					if (comma < 0) {
						break;
					}
					start = comma + 1;
				}
			}
		}
		return parts;
	}

	private void setOrRemove(String headerName, @Nullable String headerValue) {
		if (headerValue != null) {
			set(headerName, headerValue);
		}
		else {
			remove(headerName);
		}
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof HttpHeaders that) || size() != that.size()) {
			return false;
		}
		for (String headerName : headerNames()) {
			if (!getOrEmpty(headerName).equals(that.get(headerName))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = 0;
		for (String headerName : headerNames()) {
			hash += MediaType.lowerCase(headerName).hashCode() ^ getOrEmpty(headerName).hashCode();
		}
		return hash;
	}

	@Override
	public String toString() {
		StringBuilder text = new StringBuilder("[");
		for (String headerName : headerNames()) {
			if (text.length() > 1) {
				text.append(", ");
			}
			text.append(headerName).append(':');
			List<String> all = getOrEmpty(headerName);
			for (int i = 0; i < all.size(); i++) {
				text.append((i == 0) ? "\"" : ", \"").append(all.get(i)).append('"');
			}
		}
		return text.append(']').toString();
	}

	/** A view of the headers that refuses every change. */
	public static HttpHeaders readOnlyHttpHeaders(HttpHeaders headers) {
		return headers.readOnly ? headers : new HttpHeaders(headers);
	}

}
