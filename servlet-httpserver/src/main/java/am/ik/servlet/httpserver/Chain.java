package am.ik.servlet.httpserver;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;

/**
 * The filters of one dispatch, null for none, and the servlet at their end.
 */
final class Chain implements FilterChain {

	private final List<Filter> filters;

	private final ServletEntry servlet;

	private int next;

	Chain(List<Filter> filters, ServletEntry servlet) {
		this.filters = filters;
		this.servlet = servlet;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
		if (this.filters != null && this.next < this.filters.size()) {
			this.filters.get(this.next++).doFilter(request, response, this);
			return;
		}
		try {
			this.servlet.servlet().service(request, response);
		}
		catch (UnavailableException ex) {
			if (ex.isPermanent()) {
				this.servlet.unavailable();
			}
			throw ex;
		}
	}

}
