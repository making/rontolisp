/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function.support;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * The mapping of a router function: the handler of a request is the handler function the
 * router finds for it. The converters are the ones it is given, else one for
 * {@code byte[]} and one for {@code String}.
 */
public class RouterFunctionMapping implements HandlerMapping {

	private @Nullable RouterFunction<?> routerFunction;

	private List<HttpMessageConverter<?>> messageConverters = Collections.emptyList();

	public RouterFunctionMapping() {
	}

	public RouterFunctionMapping(RouterFunction<?> routerFunction) {
		this.routerFunction = routerFunction;
	}

	public void setRouterFunction(@Nullable RouterFunction<?> routerFunction) {
		this.routerFunction = routerFunction;
	}

	public @Nullable RouterFunction<?> getRouterFunction() {
		return this.routerFunction;
	}

	public void setMessageConverters(List<HttpMessageConverter<?>> messageConverters) {
		this.messageConverters = messageConverters;
	}

	@Override
	public @Nullable HandlerExecutionChain getHandler(HttpServletRequest servletRequest) throws Exception {
		if (this.routerFunction == null) {
			return null;
		}
		if (this.messageConverters.isEmpty()) {
			this.messageConverters = List.of(new ByteArrayHttpMessageConverter(), new StringHttpMessageConverter());
		}
		ServerRequest request = ServerRequest.create(servletRequest, this.messageConverters);
		Optional<? extends HandlerFunction<?>> handlerFunction = this.routerFunction.route(request);
		// the request the handler is given, there for an error handler too
		servletRequest.setAttribute(RouterFunctions.REQUEST_ATTRIBUTE, request);
		return handlerFunction.isPresent() ? new HandlerExecutionChain(handlerFunction.get()) : null;
	}

}
