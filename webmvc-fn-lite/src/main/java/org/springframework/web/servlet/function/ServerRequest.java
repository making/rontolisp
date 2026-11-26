/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.MultiValueMap;

/** The request a handler is given. */
public interface ServerRequest {

	HttpMethod method();

	URI uri();

	/** The path of the request inside its context, as it was sent. */
	String path();

	Headers headers();

	MultiValueMap<String, Cookie> cookies();

	Optional<InetSocketAddress> remoteAddress();

	List<HttpMessageConverter<?>> messageConverters();

	/**
	 * The body, read by the first converter that reads the class from the request's
	 * {@code Content-Type}; {@code HttpMediaTypeNotSupportedException} when there is
	 * none.
	 */
	<T> T body(Class<T> bodyType) throws ServletException, IOException;

	default Optional<Object> attribute(String name) {
		return Optional.ofNullable(attributes().get(name));
	}

	/** The attributes of the request, which are the servlet request's. */
	Map<String, Object> attributes();

	/** The first value of a parameter of the query string or of a form. */
	default Optional<String> param(String name) {
		List<String> values = params().get(name);
		return (values == null || values.isEmpty()) ? Optional.empty() : Optional.ofNullable(values.get(0));
	}

	MultiValueMap<String, String> params();

	/**
	 * A path variable, or {@link IllegalArgumentException} for a name the patterns did
	 * not find.
	 */
	default String pathVariable(String name) {
		Map<String, String> pathVariables = pathVariables();
		if (pathVariables.containsKey(name)) {
			return pathVariables.get(name);
		}
		throw new IllegalArgumentException("No path variable with name \"" + name + "\" available");
	}

	Map<String, String> pathVariables();

	/** The session, made when there is none. */
	HttpSession session();

	Optional<Principal> principal();

	HttpServletRequest servletRequest();

	/** A request over a servlet request, with the converters its body is read by. */
	static ServerRequest create(HttpServletRequest servletRequest, List<HttpMessageConverter<?>> messageReaders) {
		return new DefaultServerRequest(servletRequest, messageReaders);
	}

	/** The header fields of a request. */
	interface Headers {

		List<MediaType> accept();

		OptionalLong contentLength();

		Optional<MediaType> contentType();

		/** The values of a field, empty for a field that is not there. */
		List<String> header(String headerName);

		default @Nullable String firstHeader(String headerName) {
			List<String> list = header(headerName);
			return list.isEmpty() ? null : list.get(0);
		}

		HttpHeaders asHttpHeaders();

	}

}
