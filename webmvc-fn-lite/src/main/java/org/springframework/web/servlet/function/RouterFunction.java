/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.util.Optional;

/** Finds the handler of a request, if it has one. */
@FunctionalInterface
public interface RouterFunction<T extends ServerResponse> {

	Optional<HandlerFunction<T>> route(ServerRequest request);

	/** This router, then another where this one has no handler. */
	default RouterFunction<T> and(RouterFunction<T> other) {
		return request -> {
			Optional<HandlerFunction<T>> first = route(request);
			return first.isPresent() ? first : other.route(request);
		};
	}

	@SuppressWarnings("unchecked")
	default RouterFunction<?> andOther(RouterFunction<?> other) {
		RouterFunction<ServerResponse> first = (RouterFunction<ServerResponse>) this;
		return first.and((RouterFunction<ServerResponse>) other);
	}

	default RouterFunction<T> andRoute(RequestPredicate predicate, HandlerFunction<T> handlerFunction) {
		return and(RouterFunctions.route(predicate, handlerFunction));
	}

	default RouterFunction<T> andNest(RequestPredicate predicate, RouterFunction<T> routerFunction) {
		return and(RouterFunctions.nest(predicate, routerFunction));
	}

	/** This router with a filter around every handler it finds. */
	default <S extends ServerResponse> RouterFunction<S> filter(HandlerFilterFunction<T, S> filterFunction) {
		return request -> {
			Optional<HandlerFunction<T>> found = route(request);
			return found.isPresent() ? Optional.of(filterFunction.apply(found.get())) : Optional.empty();
		};
	}

}
