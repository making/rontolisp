/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;

/** A servlet request as the message a converter reads. */
public class ServletServerHttpRequest implements HttpInputMessage {

	private final HttpServletRequest servletRequest;

	private @Nullable HttpHeaders headers;

	public ServletServerHttpRequest(HttpServletRequest servletRequest) {
		this.servletRequest = servletRequest;
	}

	public HttpServletRequest getServletRequest() {
		return this.servletRequest;
	}

	/** The header fields of the request, read once. */
	@Override
	public HttpHeaders getHeaders() {
		if (this.headers == null) {
			HttpHeaders fields = new HttpHeaders();
			List<String> names = new ArrayList<>();
			drain(this.servletRequest.getHeaderNames(), names);
			for (String name : names) {
				List<String> values = new ArrayList<>(1);
				drain(this.servletRequest.getHeaders(name), values);
				fields.addAll(name, values);
			}
			this.headers = fields;
		}
		return this.headers;
	}

	/**
	 * The elements of an enumeration, into a list. By a call of itself and no loop: a
	 * loop whose test asks an enumeration is one where a program compiled from source
	 * lets its other threads go on, and every function over it is then compiled to wait
	 * (.kb/webmvc-fn-lite.md, "Written for the compiler").
	 */
	private static void drain(Enumeration<String> elements, List<String> into) {
		if (elements.hasMoreElements()) {
			into.add(elements.nextElement());
			drain(elements, into);
		}
	}

	@Override
	public InputStream getBody() throws IOException {
		return this.servletRequest.getInputStream();
	}

}
