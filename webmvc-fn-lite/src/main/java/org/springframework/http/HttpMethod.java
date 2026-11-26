/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

/** A request method: one of the constants, or any other token. */
public final class HttpMethod implements Comparable<HttpMethod>, Serializable {

	private static final long serialVersionUID = 1L;

	public static final HttpMethod GET = new HttpMethod("GET");

	public static final HttpMethod HEAD = new HttpMethod("HEAD");

	public static final HttpMethod POST = new HttpMethod("POST");

	public static final HttpMethod PUT = new HttpMethod("PUT");

	public static final HttpMethod PATCH = new HttpMethod("PATCH");

	public static final HttpMethod DELETE = new HttpMethod("DELETE");

	public static final HttpMethod OPTIONS = new HttpMethod("OPTIONS");

	public static final HttpMethod TRACE = new HttpMethod("TRACE");

	private final String name;

	private HttpMethod(String name) {
		this.name = name;
	}

	/** The constants, in the order they are declared. */
	public static HttpMethod[] values() {
		return new HttpMethod[] { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE };
	}

	/** The constant of a name, or a method of its own for another token. */
	public static HttpMethod valueOf(String method) {
		switch (method) {
			case "GET":
				return GET;
			case "HEAD":
				return HEAD;
			case "POST":
				return POST;
			case "PUT":
				return PUT;
			case "PATCH":
				return PATCH;
			case "DELETE":
				return DELETE;
			case "OPTIONS":
				return OPTIONS;
			case "TRACE":
				return TRACE;
			default:
				return new HttpMethod(method);
		}
	}

	public String name() {
		return this.name;
	}

	public boolean matches(String method) {
		return this.name.equals(method);
	}

	@Override
	public int compareTo(HttpMethod other) {
		return this.name.compareTo(other.name);
	}

	@Override
	public int hashCode() {
		return this.name.hashCode();
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || (other instanceof HttpMethod that && this.name.equals(that.name));
	}

	@Override
	public String toString() {
		return this.name;
	}

}
