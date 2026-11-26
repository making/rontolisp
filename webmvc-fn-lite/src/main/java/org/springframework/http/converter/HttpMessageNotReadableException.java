/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http.converter;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpInputMessage;

/** A body a converter could not read: answered 400. */
public class HttpMessageNotReadableException extends HttpMessageConversionException {

	private static final long serialVersionUID = 1L;

	private final transient HttpInputMessage httpInputMessage;

	public HttpMessageNotReadableException(String msg, HttpInputMessage httpInputMessage) {
		super(msg);
		this.httpInputMessage = httpInputMessage;
	}

	public HttpMessageNotReadableException(String msg, @Nullable Throwable cause, HttpInputMessage httpInputMessage) {
		super(msg, cause);
		this.httpInputMessage = httpInputMessage;
	}

	public HttpInputMessage getHttpInputMessage() {
		return this.httpInputMessage;
	}

}
