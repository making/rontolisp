package am.ik.servlet.httpserver;

import jakarta.servlet.ServletException;

/**
 * The one place a class is made an instance of by reflection: the registrations that name
 * a class instead of handing an instance.
 */
final class Instances {

	private Instances() {
	}

	static <T> T create(Class<T> type) throws ServletException {
		try {
			return type.getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			throw new ServletException("no instance of " + type.getName() + " could be made", ex);
		}
	}

	static <T> Class<? extends T> type(String className, Class<T> kind) {
		try {
			return Class.forName(className, true, Instances.class.getClassLoader()).asSubclass(kind);
		}
		catch (ClassNotFoundException | ClassCastException ex) {
			throw new IllegalArgumentException("not a class of " + kind.getName() + ": " + className, ex);
		}
	}

}
