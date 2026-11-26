/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web;

import java.util.List;

import jakarta.servlet.ServletException;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/** No converter writes the body of a response as the type it is to have: answered 406. */
public class HttpMediaTypeNotAcceptableException extends ServletException {

	private static final long serialVersionUID = 1L;

	private final transient List<MediaType> supportedMediaTypes;

	public HttpMediaTypeNotAcceptableException(List<MediaType> mediaTypes) {
		super("No acceptable representation");
		this.supportedMediaTypes = mediaTypes;
	}

	public List<MediaType> getSupportedMediaTypes() {
		return this.supportedMediaTypes;
	}

	public HttpStatusCode getStatusCode() {
		return HttpStatus.NOT_ACCEPTABLE;
	}

}
