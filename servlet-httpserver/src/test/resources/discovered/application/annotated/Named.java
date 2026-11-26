package annotated;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** A servlet with every element a registration has. */
@WebServlet(name = "named", urlPatterns = { "/named", "/named/*" }, loadOnStartup = 1,
		initParams = { @WebInitParam(name = "greeting", value = "hi"), @WebInitParam(name = "other", value = "x") })
public class Named extends HttpServlet {

	@Override
	public void init() throws ServletException {
		Events.ALL.add("init named");
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.getWriter()
			.print("named name=" + getServletName() + " greeting=" + getInitParameter("greeting") + " other="
					+ getInitParameter("other") + " info=" + request.getPathInfo() + " started="
					+ getServletContext().getAttribute("started") + " counted=" + request.getAttribute("counted"));
	}

}
