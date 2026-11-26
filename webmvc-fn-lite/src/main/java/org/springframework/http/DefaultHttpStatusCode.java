/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http;

import org.jspecify.annotations.Nullable;

/** A status code {@link HttpStatus} has no constant of. */
final class DefaultHttpStatusCode implements HttpStatusCode, Comparable<HttpStatusCode> {

	private static final long serialVersionUID = 1L;

	private final int value;

	DefaultHttpStatusCode(int value) {
		this.value = value;
	}

	@Override
	public int value() {
		return this.value;
	}

	@Override
	public boolean is1xxInformational() {
		return this.value / 100 == 1;
	}

	@Override
	public boolean is2xxSuccessful() {
		return this.value / 100 == 2;
	}

	@Override
	public boolean is3xxRedirection() {
		return this.value / 100 == 3;
	}

	@Override
	public boolean is4xxClientError() {
		return this.value / 100 == 4;
	}

	@Override
	public boolean is5xxServerError() {
		return this.value / 100 == 5;
	}

	@Override
	public boolean isError() {
		return is4xxClientError() || is5xxServerError();
	}

	@Override
	public int compareTo(HttpStatusCode other) {
		return Integer.compare(this.value, other.value());
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || (other instanceof HttpStatusCode that && this.value == that.value());
	}

	@Override
	public int hashCode() {
		return this.value;
	}

	@Override
	public String toString() {
		return Integer.toString(this.value);
	}

}
