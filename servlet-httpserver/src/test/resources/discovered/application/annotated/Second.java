package annotated;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** A filter of one servlet, named, with one parameter written without braces. */
@WebFilter(filterName = "second", servletNames = "named",
		dispatcherTypes = { DispatcherType.REQUEST, DispatcherType.FORWARD },
		initParams = @WebInitParam(name = "mark", value = "2nd"))
public class Second extends HttpFilter {

	@Override
	protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		response.addHeader("X-Filters", getInitParameter("mark") + " " + getFilterName());
		chain.doFilter(request, response);
	}

}
