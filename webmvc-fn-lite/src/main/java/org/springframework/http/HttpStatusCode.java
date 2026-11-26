/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http;

import java.io.Serializable;

/** The status code of a response: an {@link HttpStatus} or any other three-digit code. */
public interface HttpStatusCode extends Serializable {

	int value();

	boolean is1xxInformational();

	boolean is2xxSuccessful();

	boolean is3xxRedirection();

	boolean is4xxClientError();

	boolean is5xxServerError();

	boolean isError();

	default boolean isSameCodeAs(HttpStatusCode other) {
		return value() == other.value();
	}

	/** The {@link HttpStatus} of a code that has one, else a code of its own. */
	static HttpStatusCode valueOf(int code) {
		if (code < 100 || code > 999) {
			throw new IllegalArgumentException("Status code '" + code + "' should be a three-digit positive integer");
		}
		HttpStatus status = HttpStatus.resolve(code);
		return (status != null) ? status : new DefaultHttpStatusCode(code);
	}

}
