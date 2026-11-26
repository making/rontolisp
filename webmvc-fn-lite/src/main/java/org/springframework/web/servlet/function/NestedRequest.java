/*
 * Third-party: this class does what the request wrappers of RequestPredicates do in
 * Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author or
 * authors. Changed: written for this module, a part of the original's behaviour.
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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.MultiValueMap;

/**
 * A request as the routes under a path, or the right side of a conjunction, see it: the
 * path the pattern left and the path variables found so far; everything else is the
 * request's.
 */
final class NestedRequest implements ServerRequest, RequestPredicates.VariableRequest {

	private final ServerRequest request;

	private final String path;

	private Map<String, String> pathVariables;

	NestedRequest(ServerRequest request, String path, Map<String, String> pathVariables) {
		this.request = request;
		this.path = path;
		this.pathVariables = pathVariables;
	}

	@Override
	public String path() {
		return this.path;
	}

	@Override
	public Map<String, String> pathVariables() {
		return this.pathVariables;
	}

	@Override
	public void pathVariables(Map<String, String> variables) {
		this.pathVariables = variables;
	}

	@Override
	public HttpMethod method() {
		return this.request.method();
	}

	@Override
	public URI uri() {
		return this.request.uri();
	}

	@Override
	public Headers headers() {
		return this.request.headers();
	}

	@Override
	public MultiValueMap<String, Cookie> cookies() {
		return this.request.cookies();
	}

	@Override
	public Optional<InetSocketAddress> remoteAddress() {
		return this.request.remoteAddress();
	}

	@Override
	public List<HttpMessageConverter<?>> messageConverters() {
		return this.request.messageConverters();
	}

	@Override
	public <T> T body(Class<T> bodyType) throws ServletException, IOException {
		return this.request.body(bodyType);
	}

	@Override
	public Optional<Object> attribute(String name) {
		return this.request.attribute(name);
	}

	@Override
	public Map<String, Object> attributes() {
		return this.request.attributes();
	}

	@Override
	public Optional<String> param(String name) {
		return this.request.param(name);
	}

	@Override
	public MultiValueMap<String, String> params() {
		return this.request.params();
	}

	@Override
	public HttpSession session() {
		return this.request.session();
	}

	@Override
	public Optional<Principal> principal() {
		return this.request.principal();
	}

	@Override
	public HttpServletRequest servletRequest() {
		return this.request.servletRequest();
	}

	@Override
	public String toString() {
		return this.request.toString();
	}

}
