/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web;

import java.util.List;

import jakarta.servlet.ServletException;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/** The body of a request has a type no converter reads: answered 415. */
public class HttpMediaTypeNotSupportedException extends ServletException {

	private static final long serialVersionUID = 1L;

	private final transient @Nullable MediaType contentType;

	private final transient List<MediaType> supportedMediaTypes;

	public HttpMediaTypeNotSupportedException(@Nullable MediaType contentType, List<MediaType> supportedMediaTypes) {
		super("Content-Type '" + ((contentType != null) ? contentType : "") + "' is not supported");
		this.contentType = contentType;
		this.supportedMediaTypes = supportedMediaTypes;
	}

	public @Nullable MediaType getContentType() {
		return this.contentType;
	}

	public List<MediaType> getSupportedMediaTypes() {
		return this.supportedMediaTypes;
	}

	public HttpStatusCode getStatusCode() {
		return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
	}

}
