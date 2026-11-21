package am.ik.servlet.httpserver;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * A servlet reached from another one (Servlet 6.1, chapter 9), by a path inside the
 * context or by its name.
 */
final class Dispatcher implements RequestDispatcher {

	private static final String[] FORWARD = { FORWARD_REQUEST_URI, FORWARD_CONTEXT_PATH, FORWARD_SERVLET_PATH,
			FORWARD_PATH_INFO, FORWARD_QUERY_STRING, FORWARD_MAPPING };

	private static final String[] INCLUDE = { INCLUDE_REQUEST_URI, INCLUDE_CONTEXT_PATH, INCLUDE_SERVLET_PATH,
			INCLUDE_PATH_INFO, INCLUDE_QUERY_STRING, INCLUDE_MAPPING };

	private final WebContext context;

	private final Match match;

	private final String path;

	private final String query;

	/**
	 * A dispatcher.
	 * @param match the servlet
	 * @param path the path inside the context the servlet was found by, or null for a
	 * servlet found by its name
	 * @param query the query string of the path, or null
	 */
	Dispatcher(WebContext context, Match match, String path, String query) {
		this.context = context;
		this.match = match;
		this.path = path;
		this.query = query;
	}

	@Override
	public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
		Request base = Request.of(request);
		Response out = Response.of(response);
		if (out.isCommitted()) {
			throw new IllegalStateException("the response has been committed");
		}
		out.resetBuffer();
		Map<String, Object> attributes = base.attributes;
		// The attributes tell the path of the request as the client sent it: only the
		// first forward of a chain sets them.
		boolean first = this.path != null && !attributes.containsKey(FORWARD_REQUEST_URI);
		if (first) {
			set(attributes, FORWARD, new Object[] { base.getRequestURI(), base.getContextPath(), base.getServletPath(),
					base.getPathInfo(), base.getQueryString(), base.getHttpServletMapping() });
		}
		Request.Target before = base.dispatch(this.path == null ? null : this.match, this.path, this.query,
				DispatcherType.FORWARD);
		try {
			this.context.invoke(request, response, this.match, DispatcherType.FORWARD, this.path);
		}
		finally {
			base.restore(before, this.query != null);
			if (first) {
				set(attributes, FORWARD, new Object[FORWARD.length]);
			}
		}
		out.complete();
	}

	@Override
	public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException {
		Request base = Request.of(request);
		Response out = Response.of(response);
		Map<String, Object> attributes = base.attributes;
		Object[] before = null;
		if (this.path != null) {
			before = new Object[INCLUDE.length];
			for (int i = 0; i < INCLUDE.length; i++) {
				before[i] = attributes.get(INCLUDE[i]);
			}
			set(attributes, INCLUDE,
					new Object[] { this.context.getContextPath() + this.path, this.context.getContextPath(),
							this.match.servletPath, this.match.pathInfo, this.query, this.match });
		}
		Request.Target target = base.dispatch(null, this.path, this.query, DispatcherType.INCLUDE);
		out.including++;
		try {
			this.context.invoke(request, response, this.match, DispatcherType.INCLUDE, this.path);
		}
		finally {
			out.including--;
			base.restore(target, this.query != null);
			if (before != null) {
				set(attributes, INCLUDE, before);
			}
		}
	}

	private static void set(Map<String, Object> attributes, String[] names, Object[] values) {
		for (int i = 0; i < names.length; i++) {
			if (values[i] == null) {
				attributes.remove(names[i]);
			}
			else {
				attributes.put(names[i], values[i]);
			}
		}
	}

}
