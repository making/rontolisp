/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http;

import org.jspecify.annotations.Nullable;

/** A media type that cannot be read. */
public class InvalidMediaTypeException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	private final String mediaType;

	public InvalidMediaTypeException(String mediaType, @Nullable String message) {
		super((message != null) ? "Invalid media type \"" + mediaType + "\": " + message
				: "Invalid media type \"" + mediaType + "\"");
		this.mediaType = mediaType;
	}

	public String getMediaType() {
		return this.mediaType;
	}

}
