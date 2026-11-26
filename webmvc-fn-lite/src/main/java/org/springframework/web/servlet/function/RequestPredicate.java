/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.util.Optional;

/** Whether a route is for a request. */
@FunctionalInterface
public interface RequestPredicate {

	boolean test(ServerRequest request);

	default RequestPredicate and(RequestPredicate other) {
		return new RequestPredicates.AndRequestPredicate(this, other);
	}

	default RequestPredicate negate() {
		return new RequestPredicates.NegateRequestPredicate(this);
	}

	default RequestPredicate or(RequestPredicate other) {
		return new RequestPredicates.OrRequestPredicate(this, other);
	}

	/**
	 * The request the routes nested under this predicate are asked: of a path predicate,
	 * one whose path is what the pattern left; empty when this predicate does not match.
	 */
	default Optional<ServerRequest> nest(ServerRequest request) {
		return test(request) ? Optional.of(request) : Optional.empty();
	}

}
