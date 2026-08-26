package am.ik.rontolisp.runtime;

import java.lang.reflect.Modifier;
import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.HandlesTypes;

/**
 * SPIKE: registers the compiled rontolisp program with no web.xml and no
 * configuration of any kind. The container hands over every class implementing
 * {@link RontoHttpServer.Handler}, which is exactly what the JVM backend emits.
 */
@HandlesTypes(RontoHttpServer.Handler.class)
public final class RontoHttpServletInitializer implements ServletContainerInitializer {

	@Override
	public void onStartup(Set<Class<?>> handlers, ServletContext context) throws ServletException {
		Class<?> program = null;
		if (handlers != null) {
			for (Class<?> candidate : handlers) {
				if (candidate.isInterface() || Modifier.isAbstract(candidate.getModifiers())) {
					continue;
				}
				if (program != null) {
					throw new ServletException(
							"two rontolisp programs in one war: " + program.getName() + " and " + candidate.getName());
				}
				program = candidate;
			}
		}
		if (program == null) {
			throw new ServletException("no rontolisp program in this war");
		}
		context.log("rontolisp: serving " + program.getName());
		RontoHttpServer.Handler handler;
		try {
			// Triggers <clinit>, which is where a war-mode compile runs the top level.
			Class<?> initialized = Class.forName(program.getName(), true, program.getClassLoader());
			handler = (RontoHttpServer.Handler) initialized.getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException ex) {
			throw new ServletException("cannot start rontolisp program " + program.getName(), ex);
		}
		ServletRegistration.Dynamic registration = context.addServlet("rontolisp", new RontoHttpServlet(handler, !"false".equals(context.getInitParameter("rontolisp.async"))));
		registration.setLoadOnStartup(1);
		registration.setAsyncSupported(true);
		registration.addMapping("/*");
	}

}
