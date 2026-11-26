/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet;

/** The handler of a request, as a {@link HandlerMapping} answers it. */
public class HandlerExecutionChain {

	private final Object handler;

	public HandlerExecutionChain(Object handler) {
		this.handler = handler;
	}

	public Object getHandler() {
		return this.handler;
	}

}
