package packed;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** An initializer the archive's services name: it finds the annotated servlets registered. */
public class Initializer implements ServletContainerInitializer {

	@Override
	public void onStartup(Set<Class<?>> classes, ServletContext context) {
		String found = "classes=" + classes + " named=" + context.getServletRegistration("named").getMappings();
		context.addServlet("initialized", new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
				response.getWriter().print("initialized " + found);
			}
		}).addMapping("/initialized");
	}

}
