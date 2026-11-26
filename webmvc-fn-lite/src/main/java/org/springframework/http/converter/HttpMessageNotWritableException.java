/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http.converter;

import org.jspecify.annotations.Nullable;

/** A value a converter could not write: answered 500. */
public class HttpMessageNotWritableException extends HttpMessageConversionException {

	private static final long serialVersionUID = 1L;

	public HttpMessageNotWritableException(String msg) {
		super(msg);
	}

	public HttpMessageNotWritableException(String msg, @Nullable Throwable cause) {
		super(msg, cause);
	}

}
