/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.server;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/**
 * An exception that is a response: its status, with the reason as the message of the
 * error.
 */
public class ResponseStatusException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatusCode status;

	private final @Nullable String reason;

	public ResponseStatusException(HttpStatusCode status) {
		this(status, null);
	}

	public ResponseStatusException(HttpStatusCode status, @Nullable String reason) {
		this(status, reason, null);
	}

	public ResponseStatusException(int rawStatusCode, @Nullable String reason, @Nullable Throwable cause) {
		this(HttpStatusCode.valueOf(rawStatusCode), reason, cause);
	}

	public ResponseStatusException(HttpStatusCode status, @Nullable String reason, @Nullable Throwable cause) {
		super(null, cause);
		this.status = status;
		this.reason = reason;
	}

	public HttpStatusCode getStatusCode() {
		return this.status;
	}

	/** The reason, or {@code null}. */
	public @Nullable String getReason() {
		return this.reason;
	}

	/** The header fields the response is given: none, unless a subclass has some. */
	public HttpHeaders getHeaders() {
		return new HttpHeaders();
	}

	@Override
	public String getMessage() {
		return this.status + ((this.reason != null) ? " \"" + this.reason + "\"" : "");
	}

}
