/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

/** Finds the handler of a request. */
public interface HandlerMapping {

	/**
	 * The handler of a request, or {@code null} for a request this mapping has none for.
	 */
	@Nullable HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception;

}
