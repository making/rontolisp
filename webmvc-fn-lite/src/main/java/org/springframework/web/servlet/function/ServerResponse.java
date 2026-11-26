/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.ModelAndView;

/**
 * The response a handler answers with: a status, header fields, cookies and maybe a body.
 */
public interface ServerResponse {

	HttpStatusCode statusCode();

	HttpHeaders headers();

	MultiValueMap<String, Cookie> cookies();

	/** Writes this response to a servlet response; {@code null}, there being no views. */
	@Nullable ModelAndView writeTo(HttpServletRequest request, HttpServletResponse response, Context context)
			throws ServletException, IOException;

	/** A builder with the status, the header fields and the cookies of a response. */
	static BodyBuilder from(ServerResponse other) {
		return new DefaultServerResponseBuilder(other);
	}

	static BodyBuilder status(HttpStatusCode status) {
		return new DefaultServerResponseBuilder(status);
	}

	static BodyBuilder status(int status) {
		return new DefaultServerResponseBuilder(HttpStatusCode.valueOf(status));
	}

	static BodyBuilder ok() {
		return status(HttpStatus.OK);
	}

	static BodyBuilder created(URI location) {
		return status(HttpStatus.CREATED).location(location);
	}

	static BodyBuilder accepted() {
		return status(HttpStatus.ACCEPTED);
	}

	static HeadersBuilder<?> noContent() {
		return status(HttpStatus.NO_CONTENT);
	}

	static BodyBuilder seeOther(URI location) {
		return status(HttpStatus.SEE_OTHER).location(location);
	}

	static BodyBuilder temporaryRedirect(URI location) {
		return status(HttpStatus.TEMPORARY_REDIRECT).location(location);
	}

	static BodyBuilder permanentRedirect(URI location) {
		return status(HttpStatus.PERMANENT_REDIRECT).location(location);
	}

	static BodyBuilder badRequest() {
		return status(HttpStatus.BAD_REQUEST);
	}

	static HeadersBuilder<?> notFound() {
		return status(HttpStatus.NOT_FOUND);
	}

	static BodyBuilder unprocessableContent() {
		return status(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	/** The header fields of a response being built. */
	interface HeadersBuilder<B extends HeadersBuilder<B>> {

		B header(String headerName, @Nullable String... headerValues);

		B headers(Consumer<HttpHeaders> headersConsumer);

		B cookie(Cookie cookie);

		B cookies(Consumer<MultiValueMap<String, Cookie>> cookiesConsumer);

		B allow(HttpMethod... allowedMethods);

		B allow(Set<HttpMethod> allowedMethods);

		/**
		 * The entity tag: quoted when it is not. A GET or a HEAD naming it is answered
		 * 304.
		 */
		B eTag(String eTag);

		B location(URI location);

		B varyBy(String... requestHeaders);

		/** The response, with no body. */
		ServerResponse build();

		/** The response, with what a function writes to the servlet response. */
		ServerResponse build(WriteFunction writeFunction);

		/** Writes to the servlet response; {@code null}, there being no views. */
		@FunctionalInterface
		interface WriteFunction {

			@Nullable ModelAndView write(HttpServletRequest request, HttpServletResponse response) throws Exception;

		}

	}

	/** The header fields and the body of a response being built. */
	interface BodyBuilder extends HeadersBuilder<BodyBuilder> {

		BodyBuilder contentLength(long contentLength);

		BodyBuilder contentType(MediaType contentType);

		/**
		 * The response, with a body: written by the first converter that writes its class
		 * as the response's {@code Content-Type}, any type when it has none.
		 */
		ServerResponse body(Object body);

	}

	/** What a response is written with. */
	interface Context {

		List<HttpMessageConverter<?>> messageConverters();

	}

}
