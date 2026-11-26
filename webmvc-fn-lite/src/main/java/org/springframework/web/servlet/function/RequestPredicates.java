/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.servlet.function;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** The predicates routes are made of: by method, path, header fields and parameters. */
public abstract class RequestPredicates {

	/** True of every request. */
	public static RequestPredicate all() {
		return request -> true;
	}

	public static RequestPredicate method(HttpMethod httpMethod) {
		return request -> httpMethod.equals(request.method());
	}

	public static RequestPredicate methods(HttpMethod... httpMethods) {
		return request -> {
			HttpMethod method = request.method();
			for (HttpMethod httpMethod : httpMethods) {
				if (httpMethod.equals(method)) {
					return true;
				}
			}
			return false;
		};
	}

	/**
	 * True of a request whose path -- inside the context, and inside the paths it is
	 * nested under -- matches the pattern; the pattern's variables become the request's
	 * path variables. A pattern that does not start with a slash is given one.
	 */
	public static RequestPredicate path(String pattern) {
		return new PathPredicate(
				new RoutePattern((!pattern.isEmpty() && !pattern.startsWith("/")) ? "/" + pattern : pattern));
	}

	public static RequestPredicate headers(Predicate<ServerRequest.Headers> headersPredicate) {
		return request -> headersPredicate.test(request.headers());
	}

	/** True of a request whose {@code Content-Type} one of the types includes. */
	public static RequestPredicate contentType(MediaType... mediaTypes) {
		return headers(headers -> {
			MediaType contentType = headers.contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
			for (MediaType mediaType : mediaTypes) {
				if (mediaType.includes(contentType)) {
					return true;
				}
			}
			return false;
		});
	}

	/**
	 * True of a request that accepts one of the types; one with no {@code Accept} field
	 * accepts all.
	 */
	public static RequestPredicate accept(MediaType... mediaTypes) {
		return headers(headers -> {
			List<MediaType> accepted = headers.accept();
			if (accepted.isEmpty()) {
				return true;
			}
			for (MediaType acceptedMediaType : accepted) {
				for (MediaType mediaType : mediaTypes) {
					if (acceptedMediaType.isCompatibleWith(mediaType)) {
						return true;
					}
				}
			}
			return false;
		});
	}

	/** GET requests whose path matches the pattern. */
	public static RequestPredicate GET(String pattern) {
		return method(HttpMethod.GET).and(path(pattern));
	}

	/** HEAD requests whose path matches the pattern. */
	public static RequestPredicate HEAD(String pattern) {
		return method(HttpMethod.HEAD).and(path(pattern));
	}

	/** POST requests whose path matches the pattern. */
	public static RequestPredicate POST(String pattern) {
		return method(HttpMethod.POST).and(path(pattern));
	}

	/** PUT requests whose path matches the pattern. */
	public static RequestPredicate PUT(String pattern) {
		return method(HttpMethod.PUT).and(path(pattern));
	}

	/** PATCH requests whose path matches the pattern. */
	public static RequestPredicate PATCH(String pattern) {
		return method(HttpMethod.PATCH).and(path(pattern));
	}

	/** DELETE requests whose path matches the pattern. */
	public static RequestPredicate DELETE(String pattern) {
		return method(HttpMethod.DELETE).and(path(pattern));
	}

	/** OPTIONS requests whose path matches the pattern. */
	public static RequestPredicate OPTIONS(String pattern) {
		return method(HttpMethod.OPTIONS).and(path(pattern));
	}

	/** True of a request with a parameter of the value. */
	public static RequestPredicate param(String name, String value) {
		return param(name, value::equals);
	}

	public static RequestPredicate param(String name, Predicate<String> predicate) {
		return request -> {
			Optional<String> value = request.param(name);
			return value.isPresent() && predicate.test(value.get());
		};
	}

	/**
	 * Makes path variables the variables of a request: one of this module's keeps them
	 * itself, another in its attributes.
	 */
	static void pathVariables(ServerRequest request, Map<String, String> variables) {
		if (request instanceof VariableRequest target) {
			target.pathVariables(variables);
		}
		else {
			request.attributes().put(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
		}
	}

	/** The variables a request has with the ones a pattern found, which win. */
	private static Map<String, String> merged(ServerRequest request, Map<String, String> found) {
		Map<String, String> before = request.pathVariables();
		if (before.isEmpty()) {
			return found;
		}
		if (found.isEmpty()) {
			return before;
		}
		Map<String, String> all = new LinkedHashMap<>(before);
		all.putAll(found);
		return all;
	}

	/** A request that can be given path variables. */
	interface VariableRequest {

		void pathVariables(Map<String, String> variables);

	}

	/**
	 * A predicate that finds path variables. They become the request's only once the
	 * whole predicate of a route is true: {@code match} answers them, {@code test} gives
	 * them to the request.
	 */
	abstract static class VariablePredicate implements RequestPredicate {

		/**
		 * The variables found in a request this predicate is true of, else {@code null}.
		 */
		abstract @Nullable Map<String, String> match(ServerRequest request);

		@Override
		public final boolean test(ServerRequest request) {
			Map<String, String> found = match(request);
			if (found == null) {
				return false;
			}
			if (!found.isEmpty()) {
				pathVariables(request, merged(request, found));
			}
			return true;
		}

		static @Nullable Map<String, String> match(RequestPredicate predicate, ServerRequest request) {
			if (predicate instanceof VariablePredicate variable) {
				return variable.match(request);
			}
			return predicate.test(request) ? new LinkedHashMap<>() : null;
		}

	}

	private static final class PathPredicate extends VariablePredicate {

		private final RoutePattern pattern;

		PathPredicate(RoutePattern pattern) {
			this.pattern = pattern;
		}

		@Override
		@Nullable Map<String, String> match(ServerRequest request) {
			return this.pattern.match(request.path());
		}

		@Override
		public Optional<ServerRequest> nest(ServerRequest request) {
			Map<String, String> found = new LinkedHashMap<>();
			String rest = this.pattern.matchStart(request.path(), found);
			return (rest != null) ? Optional.of(new NestedRequest(request, rest, merged(request, found)))
					: Optional.empty();
		}

		@Override
		public String toString() {
			return this.pattern.text();
		}

	}

	static final class AndRequestPredicate extends VariablePredicate {

		private final RequestPredicate left;

		private final RequestPredicate right;

		AndRequestPredicate(RequestPredicate left, RequestPredicate right) {
			this.left = left;
			this.right = right;
		}

		@Override
		@Nullable Map<String, String> match(ServerRequest request) {
			Map<String, String> leftFound = match(this.left, request);
			if (leftFound == null) {
				return null;
			}
			// the right side is asked a request that has what the left one found
			ServerRequest rightRequest = leftFound.isEmpty() ? request
					: new NestedRequest(request, request.path(), merged(request, leftFound));
			Map<String, String> rightFound = match(this.right, rightRequest);
			if (rightFound == null) {
				return null;
			}
			if (rightFound.isEmpty()) {
				return leftFound;
			}
			if (leftFound.isEmpty()) {
				return rightFound;
			}
			Map<String, String> all = new LinkedHashMap<>(leftFound);
			all.putAll(rightFound);
			return all;
		}

		@Override
		public Optional<ServerRequest> nest(ServerRequest request) {
			Optional<ServerRequest> nested = this.left.nest(request);
			return nested.isPresent() ? this.right.nest(nested.get()) : nested;
		}

		@Override
		public String toString() {
			return "(" + this.left + " && " + this.right + ")";
		}

	}

	static final class OrRequestPredicate extends VariablePredicate {

		private final RequestPredicate left;

		private final RequestPredicate right;

		OrRequestPredicate(RequestPredicate left, RequestPredicate right) {
			this.left = left;
			this.right = right;
		}

		@Override
		@Nullable Map<String, String> match(ServerRequest request) {
			Map<String, String> found = match(this.left, request);
			return (found != null) ? found : match(this.right, request);
		}

		@Override
		public Optional<ServerRequest> nest(ServerRequest request) {
			Optional<ServerRequest> nested = this.left.nest(request);
			return nested.isPresent() ? nested : this.right.nest(request);
		}

		@Override
		public String toString() {
			return "(" + this.left + " || " + this.right + ")";
		}

	}

	static final class NegateRequestPredicate extends VariablePredicate {

		private final RequestPredicate delegate;

		NegateRequestPredicate(RequestPredicate delegate) {
			this.delegate = delegate;
		}

		@Override
		@Nullable Map<String, String> match(ServerRequest request) {
			return (match(this.delegate, request) == null) ? new LinkedHashMap<>() : null;
		}

		@Override
		public String toString() {
			return "!" + this.delegate;
		}

	}

}
