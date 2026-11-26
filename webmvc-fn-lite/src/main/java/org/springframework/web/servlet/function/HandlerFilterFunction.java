/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Stands around a handler: it may answer itself, change the request, or change the
 * response.
 */
@FunctionalInterface
public interface HandlerFilterFunction<T extends ServerResponse, R extends ServerResponse> {

	R filter(ServerRequest request, HandlerFunction<T> next) throws Exception;

	/** This filter around another: this one sees the request first. */
	default HandlerFilterFunction<T, R> andThen(HandlerFilterFunction<T, T> after) {
		return (request, next) -> {
			HandlerFunction<T> nextHandler = handlerRequest -> after.filter(handlerRequest, next);
			return filter(request, nextHandler);
		};
	}

	default HandlerFunction<R> apply(HandlerFunction<T> handler) {
		return request -> this.filter(request, handler);
	}

	static <T extends ServerResponse> HandlerFilterFunction<T, T> ofRequestProcessor(
			Function<ServerRequest, ServerRequest> requestProcessor) {
		return (request, next) -> next.handle(requestProcessor.apply(request));
	}

	static <T extends ServerResponse, R extends ServerResponse> HandlerFilterFunction<T, R> ofResponseProcessor(
			BiFunction<ServerRequest, T, R> responseProcessor) {
		return (request, next) -> responseProcessor.apply(request, next.handle(request));
	}

	/**
	 * A filter answering what a handler throws, and what the writing of its response
	 * throws, with the response a function makes of it.
	 */
	static <T extends ServerResponse> HandlerFilterFunction<T, T> ofErrorHandler(Predicate<Throwable> predicate,
			BiFunction<Throwable, ServerRequest, T> errorHandler) {
		return (request, next) -> {
			try {
				T t = next.handle(request);
				if (t instanceof DefaultServerResponseBuilder.BuiltResponse response) {
					response.addErrorHandler(predicate, errorHandler);
				}
				return t;
			}
			catch (Throwable throwable) {
				if (predicate.test(throwable)) {
					return errorHandler.apply(throwable, request);
				}
				throw throwable;
			}
		};
	}

}
