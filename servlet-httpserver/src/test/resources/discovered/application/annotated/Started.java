package annotated;

import java.io.IOException;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** A listener of the context and of its requests, which registers one servlet more. */
@WebListener("the start")
public class Started implements ServletContextListener, ServletRequestListener {

	@Override
	public void contextInitialized(ServletContextEvent event) {
		ServletContext context = event.getServletContext();
		Events.ALL.add("context initialized");
		context.setAttribute("started", "yes");
		context.addServlet("events", new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
				response.getWriter().print(String.join(", ", Events.ALL));
			}
		}).addMapping("/events");
	}

	@Override
	public void requestInitialized(ServletRequestEvent event) {
		event.getServletRequest().setAttribute("counted", "yes");
	}

}
