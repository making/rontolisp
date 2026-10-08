package am.ik.rontolisp.clojure;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * The host classes the lowering asks about -- a static member's arities, a throwable's
 * chain, whether a name is a class at all -- resolved through the program's Java class
 * loader ({@link ClojureFiles#javaClassLoader()}), so a class of the program's Java class
 * path lowers as a JDK class does. The loader is the one of the lowering in progress on
 * this thread: a lowering runs to completion where it starts, and one nested in it (a
 * namespace a macro loads) brings its own.
 */
final class ClojureHostClasses {

	private static final ThreadLocal<@Nullable ClassLoader> CURRENT = new ThreadLocal<>();

	private ClojureHostClasses() {
	}

	/**
	 * Runs a lowering with the classes of a loader.
	 * @param loader the program's Java class loader
	 * @param lowering the lowering
	 * @param <T> what it answers
	 * @return its answer
	 */
	static <T> T lowering(ClassLoader loader, Supplier<T> lowering) {
		ClassLoader outer = CURRENT.get();
		CURRENT.set(loader);
		try {
			return lowering.get();
		}
		finally {
			if (outer == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(outer);
			}
		}
	}

	/**
	 * Loads a class without initializing it, as {@code Class.forName} does.
	 * @param name the binary name
	 * @return the class
	 * @throws ClassNotFoundException when the program's classes have none of that name
	 */
	static Class<?> load(String name) throws ClassNotFoundException {
		ClassLoader loader = CURRENT.get();
		return Class.forName(name, false, loader != null ? loader : ClojureHostClasses.class.getClassLoader());
	}

}
