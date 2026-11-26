package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.jspecify.annotations.Nullable;

/**
 * The error pages of an application (Servlet 6.1, 10.9): by an exception's type, by a
 * status, and the one that answers any other error. Made only where the builder gives a
 * page, so a program with none holds none of the error dispatch.
 */
final class ErrorPages {

	private final WebContext context;

	private final List<Integer> statuses = new ArrayList<>();

	private final List<String> statusLocations = new ArrayList<>();

	private final List<Class<?>> types = new ArrayList<>();

	private final List<String> typeLocations = new ArrayList<>();

	private @Nullable String otherwise;

	ErrorPages(WebContext context) {
		this.context = context;
	}

	/** Refuses a location that is no path inside the context. */
	static void check(String location) {
		if (location == null || !location.startsWith("/")) {
			throw new IllegalArgumentException("an error page is a path starting with a slash: " + location);
		}
	}

	void status(int status, String location) {
		int at = this.statuses.indexOf(status);
		if (at >= 0) {
			this.statusLocations.set(at, location);
		}
		else {
			this.statuses.add(status);
			this.statusLocations.add(location);
		}
	}

	void type(Class<? extends Throwable> type, String location) {
		int at = this.types.indexOf(type);
		if (at >= 0) {
			this.typeLocations.set(at, location);
		}
		else {
			this.types.add(type);
			this.typeLocations.add(location);
		}
	}

	void otherwise(String location) {
		this.otherwise = location;
	}

	/**
	 * The page of an error: of the nearest class of the exception, else of the nearest
	 * class of the cause of a {@code ServletException} (10.9.2), else of the status, else
	 * the one of any error.
	 * @param status the status the response answers
	 * @param thrown what a servlet or a filter threw, or null
	 * @return the page's location, or null for none
	 */
	@Nullable String find(int status, @Nullable Throwable thrown) {
		String found = thrown == null ? null : byType(thrown);
		if (found == null && thrown instanceof ServletException failure) {
			Throwable cause = failure.getRootCause();
			if (cause != null) {
				found = byType(cause);
			}
		}
		if (found == null) {
			int at = this.statuses.indexOf(status);
			found = at < 0 ? null : this.statusLocations.get(at);
		}
		return found != null ? found : this.otherwise;
	}

	private @Nullable String byType(Throwable thrown) {
		for (Class<?> type = thrown.getClass(); type != null; type = type.getSuperclass()) {
			for (int i = 0; i < this.types.size(); i++) {
				if (this.types.get(i) == type) {
					return this.typeLocations.get(i);
				}
			}
		}
		return null;
	}

	/**
	 * Answers an error by its page, where there is one: an {@code ERROR} dispatch to the
	 * page, the request telling the error by its attributes. The page answers with the
	 * error's status unless it sets another.
	 * @param thrown what a servlet or a filter threw, or null for {@code sendError}
	 * @return whether a page was dispatched to
	 */
	boolean answer(Request request, Response response, int status, @Nullable String message,
			@Nullable Throwable thrown) {
		String location = find(status, thrown);
		if (location == null) {
			return false;
		}
		int question = location.indexOf('?');
		String query = question < 0 ? null : location.substring(question + 1);
		String path = UrlCodec.canonicalPath(question < 0 ? location : location.substring(0, question));
		Match match = path == null ? null : this.context.match(path);
		if (match == null) {
			this.context.log("the error page " + location + " is no servlet's");
			return false;
		}
		Map<String, Object> attributes = request.attributes;
		attributes.put(RequestDispatcher.ERROR_STATUS_CODE, status);
		attributes.put(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
		attributes.put(RequestDispatcher.ERROR_METHOD, request.getMethod());
		String servlet = request.servletName();
		if (servlet != null) {
			attributes.put(RequestDispatcher.ERROR_SERVLET_NAME, servlet);
		}
		String queryString = request.getQueryString();
		if (queryString != null) {
			attributes.put(RequestDispatcher.ERROR_QUERY_STRING, queryString);
		}
		String told = message;
		if (thrown != null) {
			// the cause a ServletException carries, as Tomcat and Jetty tell it
			Throwable cause = thrown instanceof ServletException failure ? failure.getRootCause() : null;
			Throwable error = cause != null ? cause : thrown;
			attributes.put(RequestDispatcher.ERROR_EXCEPTION, error);
			attributes.put(RequestDispatcher.ERROR_EXCEPTION_TYPE, error.getClass());
			told = thrown.getMessage();
		}
		attributes.put(RequestDispatcher.ERROR_MESSAGE, told == null ? "" : told);
		Request.Target before = request.dispatch(match, path, query, DispatcherType.ERROR);
		try {
			this.context.invoke(request, request, response, match, DispatcherType.ERROR, path);
		}
		catch (Throwable ex) {
			this.context.log("the error page " + location + " failed", ex);
		}
		finally {
			request.restore(before, query != null);
		}
		return true;
	}

}
