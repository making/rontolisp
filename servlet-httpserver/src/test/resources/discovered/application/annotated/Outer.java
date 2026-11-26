package annotated;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class Outer {

	/** A member class: its name holds a dollar sign. */
	@WebServlet("/inner")
	public static class Inner extends HttpServlet {

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			response.getWriter().print("inner name=" + getServletName());
		}

	}

	/** A class whose file names the annotation and that carries none. */
	public static class Reads {

		public static WebServlet of(Class<?> type) {
			return type.getAnnotation(WebServlet.class);
		}

	}

}
