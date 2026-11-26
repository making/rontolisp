package am.ik.servlet.httpserver;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;
import org.jspecify.annotations.Nullable;

/**
 * The filters of one dispatch, null for none, and the servlet at their end. While each
 * runs, the request supports asynchronous requests only if it and everything around it
 * does (Servlet 6.1, 2.3.3.3).
 */
final class Chain implements FilterChain {

	private final @Nullable List<FilterEntry> filters;

	private final ServletEntry servlet;

	private final Request base;

	private int next;

	Chain(@Nullable List<FilterEntry> filters, ServletEntry servlet, Request base) {
		this.filters = filters;
		this.servlet = servlet;
		this.base = base;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
		Request base = this.base;
		boolean around = base.asyncSupported;
		try {
			if (this.filters != null && this.next < this.filters.size()) {
				FilterEntry filter = this.filters.get(this.next++);
				base.asyncSupported = around && filter.asyncSupported;
				filter.filter.doFilter(request, response, this);
				return;
			}
			base.asyncSupported = around && this.servlet.asyncSupported;
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
		finally {
			base.asyncSupported = around;
		}
	}

}
