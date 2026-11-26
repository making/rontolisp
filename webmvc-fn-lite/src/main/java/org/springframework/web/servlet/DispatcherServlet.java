/*
 * Third-party: this class has the name of a class of Spring Framework 7.1 (Apache-2.0),
 * Copyright 2002-present the original author or authors, and stands for it. Changed:
 * written for this module with none of the original's code; it dispatches to handler
 * functions alone and answers an exception as the original does with nothing configured.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

/**
 * The servlet in front of the handler functions of a context: a request goes to the
 * handler the context's mappings find for it, and the handler's response is written. The
 * mappings are the context's {@link HandlerMapping}s, in the order they were registered;
 * a context with none is given one over its {@link RouterFunction}s.
 */
public class DispatcherServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	/** The name a context's handler mapping is registered under. */
	public static final String HANDLER_MAPPING_BEAN_NAME = "handlerMapping";

	private final transient WebApplicationContext webApplicationContext;

	private transient @Nullable List<HandlerMapping> handlerMappings;

	public DispatcherServlet(WebApplicationContext webApplicationContext) {
		this.webApplicationContext = webApplicationContext;
	}

	public WebApplicationContext getWebApplicationContext() {
		return this.webApplicationContext;
	}

	@Override
	public void init() throws ServletException {
		if (this.webApplicationContext instanceof StaticWebApplicationContext context
				&& context.getServletContext() == null) {
			context.setServletContext(getServletContext());
		}
		List<HandlerMapping> mappings = new ArrayList<>(
				this.webApplicationContext.getBeansOfType(HandlerMapping.class).values());
		if (mappings.isEmpty()) {
			RouterFunction<?> routes = null;
			for (Map.Entry<String, RouterFunction> bean : this.webApplicationContext
				.getBeansOfType(RouterFunction.class)
				.entrySet()) {
				routes = (routes == null) ? bean.getValue() : routes.andOther(bean.getValue());
			}
			mappings.add((routes != null) ? new RouterFunctionMapping(routes) : new RouterFunctionMapping());
		}
		this.handlerMappings = mappings;
	}

	/** Every method goes to the handler functions: a route says which it is for. */
	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			HandlerExecutionChain handler = null;
			if (this.handlerMappings != null) {
				for (HandlerMapping mapping : this.handlerMappings) {
					handler = mapping.getHandler(request);
					if (handler != null) {
						break;
					}
				}
			}
			if (handler == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND,
						"No endpoint " + request.getMethod() + " " + request.getRequestURI() + ".");
				return;
			}
			if (!(handler.getHandler() instanceof HandlerFunction<?> handlerFunction)) {
				throw new ServletException("No adapter for handler [" + handler.getHandler()
						+ "]: webmvc-fn-lite dispatches to handler functions alone");
			}
			ServerRequest serverRequest = (ServerRequest) request.getAttribute(RouterFunctions.REQUEST_ATTRIBUTE);
			ServerResponse serverResponse = handlerFunction.handle(serverRequest);
			if (serverResponse != null) {
				List<HttpMessageConverter<?>> converters = serverRequest.messageConverters();
				if (serverResponse.writeTo(request, response, () -> converters) != null) {
					throw new ServletException("webmvc-fn-lite renders no view");
				}
			}
		}
		catch (Exception ex) {
			if (!resolve(ex, request, response)) {
				if (ex instanceof ServletException servletException) {
					throw servletException;
				}
				if (ex instanceof IOException ioException) {
					throw ioException;
				}
				throw new ServletException("Request processing failed: " + ex, ex);
			}
		}
	}

	/**
	 * Answers an exception that is a response: a {@link ResponseStatusException} -- the
	 * exception or one of its causes -- by its status and reason, and the ones of the
	 * converters by the status each stands for. False for any other, which is the
	 * container's to answer.
	 */
	private boolean resolve(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
		for (Throwable cause = ex; cause instanceof Exception; cause = cause.getCause()) {
			if (cause instanceof ResponseStatusException status) {
				status.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
				sendError(response, status.getStatusCode().value(), status.getReason());
				return true;
			}
		}
		if (response.isCommitted()) {
			return ex instanceof HttpMediaTypeNotSupportedException || ex instanceof HttpMediaTypeNotAcceptableException
					|| ex instanceof HttpMessageNotReadableException || ex instanceof HttpMessageNotWritableException;
		}
		if (ex instanceof HttpMediaTypeNotSupportedException unsupported) {
			if (!unsupported.getSupportedMediaTypes().isEmpty()) {
				response.addHeader("Accept", MediaType.toString(unsupported.getSupportedMediaTypes()));
			}
			sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
					"Content-Type '" + unsupported.getContentType() + "' is not supported.");
			return true;
		}
		if (ex instanceof HttpMediaTypeNotAcceptableException unacceptable) {
			sendError(response, HttpServletResponse.SC_NOT_ACCEPTABLE,
					"Acceptable representations: " + unacceptable.getSupportedMediaTypes() + ".");
			return true;
		}
		if (ex instanceof HttpMessageNotReadableException) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, null);
			return true;
		}
		if (ex instanceof HttpMessageNotWritableException) {
			getServletContext().log("a response could not be written", ex);
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
			return true;
		}
		return false;
	}

	private void sendError(HttpServletResponse response, int status, @Nullable String message) throws IOException {
		if (message == null || message.isEmpty()) {
			response.sendError(status);
		}
		else {
			response.sendError(status, message);
		}
	}

}
