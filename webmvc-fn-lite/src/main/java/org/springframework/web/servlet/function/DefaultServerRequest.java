/*
 * Third-party: this class does what the class of the same name does in Spring Framework
 * 7.1 (Apache-2.0), Copyright 2002-present the original author or authors. Changed:
 * written for this module, a part of the original's behaviour.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.MappingMatch;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/** A request over a servlet request. */
class DefaultServerRequest implements ServerRequest, RequestPredicates.VariableRequest {

	private final ServletServerHttpRequest serverHttpRequest;

	private final List<HttpMessageConverter<?>> messageConverters;

	private @Nullable Headers headers;

	private @Nullable String path;

	private @Nullable MultiValueMap<String, String> params;

	DefaultServerRequest(HttpServletRequest servletRequest, List<HttpMessageConverter<?>> messageConverters) {
		this.serverHttpRequest = new ServletServerHttpRequest(servletRequest);
		this.messageConverters = List.copyOf(messageConverters);
	}

	@Override
	public HttpMethod method() {
		return HttpMethod.valueOf(servletRequest().getMethod());
	}

	@Override
	public URI uri() {
		String query = servletRequest().getQueryString();
		String url = servletRequest().getRequestURL().toString();
		return URI.create((query != null && !query.isEmpty()) ? url + "?" + query : url);
	}

	/**
	 * The path after the context's and, for a servlet mapped to a path prefix, after the
	 * servlet's.
	 */
	@Override
	public String path() {
		if (this.path == null) {
			HttpServletRequest request = servletRequest();
			String prefix = request.getContextPath();
			if (request.getHttpServletMapping().getMappingMatch() == MappingMatch.PATH) {
				prefix += request.getServletPath();
			}
			String uri = request.getRequestURI();
			this.path = uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
		}
		return this.path;
	}

	@Override
	public Headers headers() {
		if (this.headers == null) {
			this.headers = new DefaultRequestHeaders(
					HttpHeaders.readOnlyHttpHeaders(this.serverHttpRequest.getHeaders()));
		}
		return this.headers;
	}

	@Override
	public MultiValueMap<String, Cookie> cookies() {
		MultiValueMap<String, Cookie> result = new LinkedMultiValueMap<>();
		Cookie[] cookies = servletRequest().getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				result.add(cookie.getName(), cookie);
			}
		}
		return result;
	}

	@Override
	public Optional<InetSocketAddress> remoteAddress() {
		return Optional
			.of(new InetSocketAddress(servletRequest().getRemoteAddr(), Math.max(servletRequest().getRemotePort(), 0)));
	}

	@Override
	public List<HttpMessageConverter<?>> messageConverters() {
		return this.messageConverters;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T body(Class<T> bodyType) throws ServletException, IOException {
		MediaType contentType = headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
		for (HttpMessageConverter<?> messageConverter : this.messageConverters) {
			if (messageConverter.canRead(bodyType, contentType)) {
				return ((HttpMessageConverter<T>) messageConverter).read(bodyType, this.serverHttpRequest);
			}
		}
		List<MediaType> supported = new ArrayList<>();
		for (HttpMessageConverter<?> messageConverter : this.messageConverters) {
			supported.addAll(messageConverter.getSupportedMediaTypes(bodyType));
		}
		throw new HttpMediaTypeNotSupportedException(contentType, bySpecificity(supported));
	}

	/**
	 * The media types the converters read, the more specific ahead of the less, otherwise
	 * in their order: one pass after another over neighbours, swapping a pair whose first
	 * is the less specific.
	 */
	private static List<MediaType> bySpecificity(List<MediaType> mediaTypes) {
		for (int pass = 0; pass < mediaTypes.size(); pass++) {
			for (int i = 1; i < mediaTypes.size() - pass; i++) {
				MediaType before = mediaTypes.get(i - 1);
				MediaType after = mediaTypes.get(i);
				if (moreSpecific(after, before)) {
					mediaTypes.set(i - 1, after);
					mediaTypes.set(i, before);
				}
			}
		}
		return mediaTypes;
	}

	/**
	 * Whether one media type is more specific than another: a higher quality, else a
	 * concrete type over a wildcard one, a concrete subtype over a wildcard one
	 * ({@code *} or {@code *+suffix}), and of the same type and subtype the one with more
	 * parameters.
	 */
	private static boolean moreSpecific(MediaType one, MediaType other) {
		if (one.getQualityValue() != other.getQualityValue()) {
			return one.getQualityValue() > other.getQualityValue();
		}
		if (one.isWildcardType() != other.isWildcardType()) {
			return other.isWildcardType();
		}
		if (one.isWildcardSubtype() != other.isWildcardSubtype()) {
			return other.isWildcardSubtype();
		}
		return one.getType().equals(other.getType()) && one.getSubtype().equals(other.getSubtype())
				&& one.getParameters().size() > other.getParameters().size();
	}

	@Override
	public Map<String, Object> attributes() {
		return new ServletAttributesMap(servletRequest());
	}

	@Override
	public Optional<Object> attribute(String name) {
		return Optional.ofNullable(servletRequest().getAttribute(name));
	}

	@Override
	public Optional<String> param(String name) {
		return Optional.ofNullable(servletRequest().getParameter(name));
	}

	@Override
	public MultiValueMap<String, String> params() {
		if (this.params == null) {
			MultiValueMap<String, String> all = new LinkedMultiValueMap<>();
			for (Map.Entry<String, String[]> parameter : servletRequest().getParameterMap().entrySet()) {
				all.put(parameter.getKey(), List.of(parameter.getValue()));
			}
			this.params = all;
		}
		return this.params;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, String> pathVariables() {
		Map<String, String> pathVariables = (Map<String, String>) servletRequest()
			.getAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		return (pathVariables != null) ? pathVariables : new LinkedHashMap<>();
	}

	@Override
	public void pathVariables(Map<String, String> variables) {
		servletRequest().setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
				Collections.unmodifiableMap(variables));
	}

	@Override
	public HttpSession session() {
		return servletRequest().getSession(true);
	}

	@Override
	public Optional<Principal> principal() {
		return Optional.ofNullable(servletRequest().getUserPrincipal());
	}

	@Override
	public HttpServletRequest servletRequest() {
		return this.serverHttpRequest.getServletRequest();
	}

	@Override
	public String toString() {
		return "HTTP " + method() + " " + path();
	}

	private static final class DefaultRequestHeaders implements Headers {

		private final HttpHeaders httpHeaders;

		DefaultRequestHeaders(HttpHeaders httpHeaders) {
			this.httpHeaders = httpHeaders;
		}

		@Override
		public List<MediaType> accept() {
			return this.httpHeaders.getAccept();
		}

		@Override
		public OptionalLong contentLength() {
			long value = this.httpHeaders.getContentLength();
			return (value != -1) ? OptionalLong.of(value) : OptionalLong.empty();
		}

		@Override
		public Optional<MediaType> contentType() {
			return Optional.ofNullable(this.httpHeaders.getContentType());
		}

		@Override
		public List<String> header(String headerName) {
			return this.httpHeaders.getOrEmpty(headerName);
		}

		@Override
		public HttpHeaders asHttpHeaders() {
			return this.httpHeaders;
		}

		@Override
		public String toString() {
			return this.httpHeaders.toString();
		}

	}

	/**
	 * The attributes of a servlet request as a map: a read and a write by name go to the
	 * request; what walks the map walks a copy of the attributes as they are then.
	 */
	private static final class ServletAttributesMap implements Map<String, Object> {

		private final HttpServletRequest servletRequest;

		ServletAttributesMap(HttpServletRequest servletRequest) {
			this.servletRequest = servletRequest;
		}

		@Override
		public boolean containsKey(Object key) {
			return key instanceof String name && this.servletRequest.getAttribute(name) != null;
		}

		@Override
		public @Nullable Object get(Object key) {
			return (key instanceof String name) ? this.servletRequest.getAttribute(name) : null;
		}

		@Override
		public @Nullable Object put(String key, Object value) {
			Object before = this.servletRequest.getAttribute(key);
			this.servletRequest.setAttribute(key, value);
			return before;
		}

		@Override
		public @Nullable Object remove(Object key) {
			if (!(key instanceof String name)) {
				return null;
			}
			Object before = this.servletRequest.getAttribute(name);
			this.servletRequest.removeAttribute(name);
			return before;
		}

		@Override
		public void putAll(Map<? extends String, ?> attributes) {
			for (Map.Entry<? extends String, ?> attribute : attributes.entrySet()) {
				this.servletRequest.setAttribute(attribute.getKey(), attribute.getValue());
			}
		}

		@Override
		public void clear() {
			for (String name : entries().keySet()) {
				this.servletRequest.removeAttribute(name);
			}
		}

		@Override
		public int size() {
			return entries().size();
		}

		@Override
		public boolean isEmpty() {
			return entries().isEmpty();
		}

		@Override
		public boolean containsValue(Object value) {
			return entries().containsValue(value);
		}

		@Override
		public Set<String> keySet() {
			return entries().keySet();
		}

		@Override
		public Collection<Object> values() {
			return entries().values();
		}

		@Override
		public Set<Map.Entry<String, Object>> entrySet() {
			return entries().entrySet();
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return this == other || entries().equals(other);
		}

		@Override
		public int hashCode() {
			return entries().hashCode();
		}

		@Override
		public String toString() {
			return entries().toString();
		}

		/** The attributes as they are now: a copy. */
		private LinkedHashMap<String, Object> entries() {
			LinkedHashMap<String, Object> entries = new LinkedHashMap<>();
			collect(this.servletRequest.getAttributeNames(), entries);
			return entries;
		}

		/** By a call of itself and no loop, as {@code ServletServerHttpRequest}'s. */
		private void collect(Enumeration<String> names, LinkedHashMap<String, Object> into) {
			if (names.hasMoreElements()) {
				String name = names.nextElement();
				into.put(name, this.servletRequest.getAttribute(name));
				collect(names, into);
			}
		}

	}

}
