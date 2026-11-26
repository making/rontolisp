package annotated;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Filters of one path, which the specification gives no order. */
public class Order {

	@WebFilter("/inner")
	public static class Zed extends Mark {

	}

	@WebFilter("/inner")
	public static class Mid extends Mark {

	}

	@WebFilter("/inner")
	public static class Abe extends Mark {

	}

	public abstract static class Mark extends HttpFilter {

		@Override
		protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			response.addHeader("X-Order", getClass().getSimpleName());
			chain.doFilter(request, response);
		}

	}

}
