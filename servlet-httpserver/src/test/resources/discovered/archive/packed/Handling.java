package packed;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.EventListener;
import java.util.Set;
import java.util.TreeSet;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.HandlesTypes;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * An initializer that asks for classes: an interface of the application, an annotation
 * of it, and an interface of the JDK.
 */
@HandlesTypes({ Handling.Plugin.class, Handling.Marked.class, EventListener.class })
public class Handling implements ServletContainerInitializer {

	public interface Plugin {

	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
	public @interface Marked {

	}

	@Override
	public void onStartup(Set<Class<?>> classes, ServletContext context) {
		Set<String> names = new TreeSet<>();
		for (Class<?> type : classes) {
			names.add(type.getName());
		}
		context.addServlet("handled", new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
				response.getWriter().print(String.join(" ", names));
			}
		}).addMapping("/handled");
	}

}
