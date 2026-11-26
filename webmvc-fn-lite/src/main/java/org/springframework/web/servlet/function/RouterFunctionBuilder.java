/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.http.HttpMethod;

/** The builder {@link RouterFunctions#route()} hands out. */
class RouterFunctionBuilder implements RouterFunctions.Builder {

	private final List<RouterFunction<ServerResponse>> routerFunctions = new ArrayList<>();

	private final List<HandlerFilterFunction<ServerResponse, ServerResponse>> filterFunctions = new ArrayList<>();

	private final List<HandlerFilterFunction<ServerResponse, ServerResponse>> errorHandlers = new ArrayList<>();

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder GET(HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.GET), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder GET(String pattern, HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.GET(pattern), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder GET(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.GET).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder GET(String pattern, RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.GET(pattern).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder HEAD(HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.HEAD), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder HEAD(String pattern, HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.HEAD(pattern), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder HEAD(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.HEAD).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder HEAD(String pattern, RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.HEAD(pattern).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder POST(HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.POST), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder POST(String pattern, HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.POST(pattern), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder POST(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.POST).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder POST(String pattern, RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.POST(pattern).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PUT(HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.PUT), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PUT(String pattern, HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.PUT(pattern), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PUT(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.PUT).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PUT(String pattern, RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.PUT(pattern).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PATCH(HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.PATCH), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PATCH(String pattern,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.PATCH(pattern), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PATCH(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.PATCH).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder PATCH(String pattern, RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.PATCH(pattern).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder DELETE(HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.DELETE), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder DELETE(String pattern,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.DELETE(pattern), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder DELETE(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.DELETE).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder DELETE(String pattern, RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.DELETE(pattern).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder OPTIONS(HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.OPTIONS), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder OPTIONS(String pattern,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.OPTIONS(pattern), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder OPTIONS(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.method(HttpMethod.OPTIONS).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder OPTIONS(String pattern, RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return route(RequestPredicates.OPTIONS(pattern).and(predicate), handlerFunction);
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder route(RequestPredicate predicate,
			HandlerFunction<T> handlerFunction) {
		return add(RouterFunctions.route(predicate, handlerFunction));
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends ServerResponse> RouterFunctions.Builder add(RouterFunction<T> routerFunction) {
		this.routerFunctions.add((RouterFunction<ServerResponse>) routerFunction);
		return this;
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder nest(RequestPredicate predicate,
			Supplier<RouterFunction<T>> routerFunctionSupplier) {
		return add(RouterFunctions.nest(predicate, routerFunctionSupplier.get()));
	}

	@Override
	public RouterFunctions.Builder nest(RequestPredicate predicate, Consumer<RouterFunctions.Builder> builderConsumer) {
		RouterFunctionBuilder nestedBuilder = new RouterFunctionBuilder();
		builderConsumer.accept(nestedBuilder);
		return add(RouterFunctions.nest(predicate, nestedBuilder.build()));
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder path(String pattern,
			Supplier<RouterFunction<T>> routerFunctionSupplier) {
		return nest(RequestPredicates.path(pattern), routerFunctionSupplier);
	}

	@Override
	public RouterFunctions.Builder path(String pattern, Consumer<RouterFunctions.Builder> builderConsumer) {
		return nest(RequestPredicates.path(pattern), builderConsumer);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends ServerResponse, R extends ServerResponse> RouterFunctions.Builder filter(
			HandlerFilterFunction<T, R> filterFunction) {
		this.filterFunctions.add((HandlerFilterFunction<ServerResponse, ServerResponse>) filterFunction);
		return this;
	}

	@Override
	public RouterFunctions.Builder before(Function<ServerRequest, ServerRequest> requestProcessor) {
		return filter(HandlerFilterFunction.<ServerResponse>ofRequestProcessor(requestProcessor));
	}

	@Override
	public <T extends ServerResponse, R extends ServerResponse> RouterFunctions.Builder after(
			BiFunction<ServerRequest, T, R> responseProcessor) {
		return filter(HandlerFilterFunction.ofResponseProcessor(responseProcessor));
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends ServerResponse> RouterFunctions.Builder onError(Predicate<Throwable> predicate,
			BiFunction<Throwable, ServerRequest, T> responseProvider) {
		HandlerFilterFunction<T, T> filter = HandlerFilterFunction.ofErrorHandler(predicate, responseProvider);
		// the handler added last is the outermost: it is asked last
		this.errorHandlers.add(0, (HandlerFilterFunction<ServerResponse, ServerResponse>) filter);
		return this;
	}

	@Override
	public <T extends ServerResponse> RouterFunctions.Builder onError(Class<? extends Throwable> exceptionType,
			BiFunction<Throwable, ServerRequest, T> responseProvider) {
		return onError(exceptionType::isInstance, responseProvider);
	}

	@Override
	public RouterFunction<ServerResponse> build() {
		if (this.routerFunctions.isEmpty()) {
			throw new IllegalStateException("No routes registered. Register a route with GET(), POST(), etc.");
		}
		List<RouterFunction<ServerResponse>> routes = new ArrayList<>(this.routerFunctions);
		RouterFunction<ServerResponse> result = request -> {
			for (RouterFunction<ServerResponse> route : routes) {
				Optional<HandlerFunction<ServerResponse>> found = route.route(request);
				if (found.isPresent()) {
					return found;
				}
			}
			return Optional.empty();
		};
		// the filters in their order, then the error handlers, each around what follows
		HandlerFilterFunction<ServerResponse, ServerResponse> filter = null;
		for (HandlerFilterFunction<ServerResponse, ServerResponse> next : this.filterFunctions) {
			filter = (filter == null) ? next : filter.andThen(next);
		}
		for (HandlerFilterFunction<ServerResponse, ServerResponse> next : this.errorHandlers) {
			filter = (filter == null) ? next : filter.andThen(next);
		}
		return (filter != null) ? result.filter(filter) : result;
	}

}
