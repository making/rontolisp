package annotated;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** A servlet by its value alone: its name is its class's. Its parts are small. */
@WebServlet("/hello")
@MultipartConfig(maxFileSize = 4)
public class Hello extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.getWriter().print("hello name=" + getServletName() + " greeting=" + getInitParameter("greeting"));
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {
		try {
			response.getWriter().print("a=" + new String(request.getPart("a").getInputStream().readAllBytes()));
		}
		catch (IllegalStateException | ServletException ex) {
			// Jetty wraps the IllegalStateException of a limit
			response.getWriter().print("refused");
		}
	}

}
