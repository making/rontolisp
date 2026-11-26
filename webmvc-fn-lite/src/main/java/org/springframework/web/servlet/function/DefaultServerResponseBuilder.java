/*
 * Third-party: this class does what the response builders and their responses do in
 * Spring Framework 7.1 (DefaultServerResponseBuilder, DefaultEntityResponseBuilder,
 * AbstractServerResponse, ErrorHandlingServerResponse; Apache-2.0), Copyright
 * 2002-present the original author or authors. Changed: written for this module, a part
 * of the original's behaviour.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.servlet.ModelAndView;

/** The builder of {@link ServerResponse}'s factories, and the responses it builds. */
class DefaultServerResponseBuilder implements ServerResponse.BodyBuilder {

	private final HttpStatusCode statusCode;

	private final HttpHeaders headers = new HttpHeaders();

	private final MultiValueMap<String, Cookie> cookies = new LinkedMultiValueMap<>();

	/**
	 * What hands the cookies to the servlet response: there only once a cookie may have
	 * been added, so that a response with none never names the Servlet API's cookie.
	 */
	private @Nullable BiConsumer<MultiValueMap<String, Cookie>, HttpServletResponse> cookieWriter;

	DefaultServerResponseBuilder(HttpStatusCode statusCode) {
		this.statusCode = statusCode;
	}

	DefaultServerResponseBuilder(ServerResponse other) {
		this.statusCode = other.statusCode();
		this.headers.addAll(other.headers());
		if (other instanceof BuiltResponse built) {
			this.cookies.addAll(built.cookies);
			this.cookieWriter = built.cookieWriter;
		}
		else if (!other.cookies().isEmpty()) {
			throw new UnsupportedOperationException("the cookies of a response of another class are not copied");
		}
	}

	@Override
	public ServerResponse.BodyBuilder header(String headerName, @Nullable String... headerValues) {
		for (String headerValue : headerValues) {
			this.headers.add(headerName, headerValue);
		}
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder headers(Consumer<HttpHeaders> headersConsumer) {
		headersConsumer.accept(this.headers);
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder cookie(Cookie cookie) {
		this.cookies.add(cookie.getName(), cookie);
		this.cookieWriter = DefaultServerResponseBuilder::writeCookies;
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder cookies(Consumer<MultiValueMap<String, Cookie>> cookiesConsumer) {
		cookiesConsumer.accept(this.cookies);
		this.cookieWriter = DefaultServerResponseBuilder::writeCookies;
		return this;
	}

	private static void writeCookies(MultiValueMap<String, Cookie> cookies, HttpServletResponse response) {
		for (List<Cookie> named : cookies.values()) {
			for (Cookie cookie : named) {
				response.addCookie(cookie);
			}
		}
	}

	@Override
	public ServerResponse.BodyBuilder allow(HttpMethod... allowedMethods) {
		return allow(new LinkedHashSet<>(List.of(allowedMethods)));
	}

	@Override
	public ServerResponse.BodyBuilder allow(Set<HttpMethod> allowedMethods) {
		this.headers.setAllow(allowedMethods);
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder contentLength(long contentLength) {
		this.headers.setContentLength(contentLength);
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder contentType(MediaType contentType) {
		this.headers.setContentType(contentType);
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder eTag(String tag) {
		if (!tag.startsWith("\"") && !tag.startsWith("W/\"")) {
			tag = "\"" + tag;
		}
		if (!tag.endsWith("\"")) {
			tag = tag + "\"";
		}
		this.headers.setETag(tag);
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder location(URI location) {
		this.headers.setLocation(location);
		return this;
	}

	@Override
	public ServerResponse.BodyBuilder varyBy(String... requestHeaders) {
		this.headers.setVary(List.of(requestHeaders));
		return this;
	}

	@Override
	public ServerResponse build() {
		return build((request, response) -> null);
	}

	@Override
	public ServerResponse build(WriteFunction writeFunction) {
		return new BuiltResponse(this, writeFunction, null);
	}

	@Override
	public ServerResponse body(Object body) {
		if (body == null) {
			throw new IllegalArgumentException("Body must not be null");
		}
		return new BuiltResponse(this, null, body);
	}

	/**
	 * A built response: the status, the fields and the cookies, then either what a
	 * function writes or a body a converter writes. It keeps the error handlers the
	 * routes it came through answer a failed write with.
	 */
	static final class BuiltResponse implements ServerResponse {

		private final HttpStatusCode statusCode;

		private final HttpHeaders headers;

		private final MultiValueMap<String, Cookie> cookies;

		private final @Nullable BiConsumer<MultiValueMap<String, Cookie>, HttpServletResponse> cookieWriter;

		// What writes the response, or null where a converter writes the entity.
		private final @Nullable WriteFunction writeFunction;

		private final @Nullable Object entity;

		private @Nullable List<Predicate<Throwable>> errorPredicates;

		private @Nullable List<BiFunction<Throwable, ServerRequest, ? extends ServerResponse>> errorHandlers;

		BuiltResponse(DefaultServerResponseBuilder builder, @Nullable WriteFunction writeFunction,
				@Nullable Object entity) {
			this.statusCode = builder.statusCode;
			HttpHeaders copy = new HttpHeaders();
			copy.addAll(builder.headers);
			this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
			this.cookies = new LinkedMultiValueMap<>(builder.cookies);
			this.cookieWriter = builder.cookieWriter;
			this.writeFunction = writeFunction;
			this.entity = entity;
		}

		@Override
		public HttpStatusCode statusCode() {
			return this.statusCode;
		}

		@Override
		public HttpHeaders headers() {
			return this.headers;
		}

		@Override
		public MultiValueMap<String, Cookie> cookies() {
			return this.cookies;
		}

		<T extends ServerResponse> void addErrorHandler(Predicate<Throwable> predicate,
				BiFunction<Throwable, ServerRequest, T> errorHandler) {
			List<Predicate<Throwable>> predicates = this.errorPredicates;
			List<BiFunction<Throwable, ServerRequest, ? extends ServerResponse>> handlers = this.errorHandlers;
			if (predicates == null || handlers == null) {
				predicates = new ArrayList<>();
				handlers = new ArrayList<>();
				this.errorPredicates = predicates;
				this.errorHandlers = handlers;
			}
			predicates.add(predicate);
			handlers.add(errorHandler);
		}

		@Override
		public @Nullable ModelAndView writeTo(HttpServletRequest request, HttpServletResponse response, Context context)
				throws ServletException, IOException {
			try {
				response.setStatus(this.statusCode.value());
				for (String name : this.headers.headerNames()) {
					for (String value : this.headers.getOrEmpty(name)) {
						response.addHeader(name, value);
					}
				}
				long contentLength = this.headers.getContentLength();
				if (contentLength != -1) {
					response.setContentLengthLong(contentLength);
				}
				if (this.cookieWriter != null) {
					this.cookieWriter.accept(this.cookies, response);
				}
				if (notModified(request, response)) {
					return null;
				}
				if (this.entity != null) {
					writeEntity(this.entity, response, context);
					return null;
				}
				return Objects.requireNonNull(this.writeFunction).write(request, response);
			}
			catch (Throwable throwable) {
				return handleError(throwable, request, response, context);
			}
		}

		/**
		 * Whether a GET or a HEAD of a 200 names this response's entity tag in
		 * {@code If-None-Match}: it is then answered 304, with no body.
		 */
		private boolean notModified(HttpServletRequest request, HttpServletResponse response) {
			String tag = this.headers.getETag();
			if (tag == null || this.statusCode.value() != 200
					|| !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
				return false;
			}
			String strong = tag.startsWith("W/") ? tag.substring(2) : tag;
			for (String field : new ServletServerHttpRequest(request).getHeaders()
				.getOrEmpty(HttpHeaders.IF_NONE_MATCH)) {
				int start = 0;
				while (start < field.length()) {
					int comma = field.indexOf(',', start);
					String named = field.substring(start, (comma < 0) ? field.length() : comma).strip();
					if (strong.equals(named.startsWith("W/") ? named.substring(2) : named)) {
						response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
						return true;
					}
					start = (comma < 0) ? field.length() : comma + 1;
				}
			}
			return false;
		}

		@SuppressWarnings("unchecked")
		private void writeEntity(Object entity, HttpServletResponse response, Context context)
				throws ServletException, IOException {
			MediaType contentType = null;
			if (response.getContentType() != null) {
				try {
					contentType = MediaType.parseMediaType(response.getContentType()).removeQualityValue();
				}
				catch (InvalidMediaTypeException ex) {
					// as with no type
				}
			}
			Class<?> entityClass = entity.getClass();
			for (HttpMessageConverter<?> messageConverter : context.messageConverters()) {
				if (messageConverter.canWrite(entityClass, contentType)) {
					ServletServerHttpResponse message = new ServletServerHttpResponse(response);
					((HttpMessageConverter<Object>) messageConverter).write(entity, contentType, message);
					message.close();
					return;
				}
			}
			List<MediaType> producible = new ArrayList<>();
			for (HttpMessageConverter<?> messageConverter : context.messageConverters()) {
				if (messageConverter.canWrite(entityClass, null)) {
					producible.addAll(messageConverter.getSupportedMediaTypes(entityClass));
				}
			}
			throw new HttpMediaTypeNotAcceptableException(producible);
		}

		private @Nullable ModelAndView handleError(Throwable throwable, HttpServletRequest request,
				HttpServletResponse response, Context context) throws ServletException, IOException {
			if (this.errorPredicates != null && this.errorHandlers != null) {
				for (int i = 0; i < this.errorPredicates.size(); i++) {
					if (this.errorPredicates.get(i).test(throwable)) {
						ServerRequest serverRequest = (ServerRequest) request
							.getAttribute(RouterFunctions.REQUEST_ATTRIBUTE);
						return this.errorHandlers.get(i)
							.apply(throwable, serverRequest)
							.writeTo(request, response, context);
					}
				}
			}
			if (throwable instanceof ServletException servletException) {
				throw servletException;
			}
			if (throwable instanceof IOException ioException) {
				throw ioException;
			}
			throw new ServletException(throwable);
		}

	}

}
