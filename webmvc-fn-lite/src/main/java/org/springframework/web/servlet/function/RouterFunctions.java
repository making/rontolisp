/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Where routes are made: a builder, or a predicate and a handler put together. */
public abstract class RouterFunctions {

	/**
	 * The request attribute holding the {@link ServerRequest} a handler is given. The
	 * name is this class's, written out: a constant.
	 */
	public static final String REQUEST_ATTRIBUTE = "org.springframework.web.servlet.function.RouterFunctions.request";

	/**
	 * The request attribute holding the path variables, a {@code Map<String, String>}.
	 */
	public static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE = "org.springframework.web.servlet.function.RouterFunctions.uriTemplateVariables";

	public static Builder route() {
		return new RouterFunctionBuilder();
	}

	/** A route: the handler of the requests the predicate is true of. */
	public static <T extends ServerResponse> RouterFunction<T> route(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return request -> predicate.test(request) ? Optional.of(handlerFunction) : Optional.empty();
	}

	/**
	 * Routes under a predicate: asked only the requests the predicate is true of, and,
	 * under a path predicate, with the path the pattern left.
	 */
	public static <T extends ServerResponse> RouterFunction<T> nest(RequestPredicate predicate,
			RouterFunction<T> routerFunction) {
		return request -> {
			Optional<ServerRequest> nested = predicate.nest(request);
			if (nested.isEmpty()) {
				return Optional.empty();
			}
			Optional<HandlerFunction<T>> found = routerFunction.route(nested.get());
			if (found.isPresent() && nested.get() != request) {
				// what the path predicates on the way found is the request's from here on
				RequestPredicates.pathVariables(request, nested.get().pathVariables());
			}
			return found;
		};
	}

	/**
	 * Collects routes, the routes nested under a predicate, filters and error handlers.
	 */
	public interface Builder {

		/** A route for GET requests. */
		<T extends ServerResponse> Builder GET(HandlerFunction<T> handlerFunction);

		/** A route for GET requests whose path matches the pattern. */
		<T extends ServerResponse> Builder GET(String pattern, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder GET(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder GET(String pattern, RequestPredicate predicate,
				HandlerFunction<T> handlerFunction);

		/** A route for HEAD requests. */
		<T extends ServerResponse> Builder HEAD(HandlerFunction<T> handlerFunction);

		/** A route for HEAD requests whose path matches the pattern. */
		<T extends ServerResponse> Builder HEAD(String pattern, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder HEAD(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder HEAD(String pattern, RequestPredicate predicate,
				HandlerFunction<T> handlerFunction);

		/** A route for POST requests. */
		<T extends ServerResponse> Builder POST(HandlerFunction<T> handlerFunction);

		/** A route for POST requests whose path matches the pattern. */
		<T extends ServerResponse> Builder POST(String pattern, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder POST(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder POST(String pattern, RequestPredicate predicate,
				HandlerFunction<T> handlerFunction);

		/** A route for PUT requests. */
		<T extends ServerResponse> Builder PUT(HandlerFunction<T> handlerFunction);

		/** A route for PUT requests whose path matches the pattern. */
		<T extends ServerResponse> Builder PUT(String pattern, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder PUT(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder PUT(String pattern, RequestPredicate predicate,
				HandlerFunction<T> handlerFunction);

		/** A route for PATCH requests. */
		<T extends ServerResponse> Builder PATCH(HandlerFunction<T> handlerFunction);

		/** A route for PATCH requests whose path matches the pattern. */
		<T extends ServerResponse> Builder PATCH(String pattern, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder PATCH(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder PATCH(String pattern, RequestPredicate predicate,
				HandlerFunction<T> handlerFunction);

		/** A route for DELETE requests. */
		<T extends ServerResponse> Builder DELETE(HandlerFunction<T> handlerFunction);

		/** A route for DELETE requests whose path matches the pattern. */
		<T extends ServerResponse> Builder DELETE(String pattern, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder DELETE(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder DELETE(String pattern, RequestPredicate predicate,
				HandlerFunction<T> handlerFunction);

		/** A route for OPTIONS requests. */
		<T extends ServerResponse> Builder OPTIONS(HandlerFunction<T> handlerFunction);

		/** A route for OPTIONS requests whose path matches the pattern. */
		<T extends ServerResponse> Builder OPTIONS(String pattern, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder OPTIONS(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder OPTIONS(String pattern, RequestPredicate predicate,
				HandlerFunction<T> handlerFunction);

		/** A route for the requests a predicate is true of, whatever the method. */
		<T extends ServerResponse> Builder route(RequestPredicate predicate, HandlerFunction<T> handlerFunction);

		<T extends ServerResponse> Builder add(RouterFunction<T> routerFunction);

		<T extends ServerResponse> Builder nest(RequestPredicate predicate,
				Supplier<RouterFunction<T>> routerFunctionSupplier);

		Builder nest(RequestPredicate predicate, Consumer<Builder> builderConsumer);

		<T extends ServerResponse> Builder path(String pattern, Supplier<RouterFunction<T>> routerFunctionSupplier);

		/** Routes under a path: their patterns go on from where this one ends. */
		Builder path(String pattern, Consumer<Builder> builderConsumer);

		/**
		 * A filter around every route of this builder; the first added is the outermost.
		 */
		<T extends ServerResponse, R extends ServerResponse> Builder filter(HandlerFilterFunction<T, R> filterFunction);

		Builder before(Function<ServerRequest, ServerRequest> requestProcessor);

		<T extends ServerResponse, R extends ServerResponse> Builder after(
				BiFunction<ServerRequest, T, R> responseProcessor);

		/**
		 * The response to what a route of this builder throws, or the writing of its
		 * response.
		 */
		<T extends ServerResponse> Builder onError(Predicate<Throwable> predicate,
				BiFunction<Throwable, ServerRequest, T> responseProvider);

		<T extends ServerResponse> Builder onError(Class<? extends Throwable> exceptionType,
				BiFunction<Throwable, ServerRequest, T> responseProvider);

		RouterFunction<ServerResponse> build();

	}

}
