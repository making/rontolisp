package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.jar.Manifest;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.HandlesTypes;

/**
 * Registers the compiled rontolisp program with no {@code web.xml} and no configuration
 * of any kind. The JVM backend emits an {@code http-handler} program as
 * {@code public class App implements RontoHttpServer.Handler}, so
 * {@code @HandlesTypes(RontoHttpServer.Handler.class)} makes the container hand this
 * initializer exactly that class -- the war carries no name, no parameter and no
 * generated code, only the one-line service declaration naming this class.
 *
 * <p>
 * {@code Class.forName(name, true, loader)} is what runs the program's top level: a
 * container loads {@code @HandlesTypes} candidates WITHOUT initializing them, and a war
 * compile puts the top level in {@code <clinit>} (the {@code rontolisp:jvm-export}
 * arrangement, forced on by war mode), so the initializer has to ask for initialization
 * explicitly. A top level that signals surfaces as {@code ExceptionInInitializerError}
 * and poisons the class permanently ({@code .kb/jvm-export.md}) -- caught here and
 * rethrown as {@link ServletException} so it fails the DEPLOYMENT, which is where a
 * broken program belongs, rather than 500ing forever.
 *
 * <p>
 * The {@code jakarta.servlet} import is this package's one sanctioned exception; see
 * {@link RontoHttpServlet}.
 */
@HandlesTypes(RontoHttpServer.Handler.class)
public final class RontoHttpServletInitializer implements ServletContainerInitializer {

	@Override
	public void onStartup(Set<Class<?>> handlers, ServletContext context) throws ServletException {
		Class<?> program = null;
		if (handlers != null) {
			for (Class<?> candidate : handlers) {
				// Containers differ on whether the annotated interface itself (or an
				// abstract implementor) appears in the handed set.
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
			// A war with no http-handler is refused at compile time; this is the
			// backstop for a hand-assembled war.
			throw new ServletException("no rontolisp http-handler program in this war: no concrete class implements "
					+ RontoHttpServer.Handler.class.getName());
		}
		context.log("rontolisp: serving " + program.getName());
		RontoHttpServer.Handler handler;
		try {
			// Runs the top level (defvar initialization, the handler registration):
			// instantiating would trigger it too, but the ordering is load-bearing, so
			// it is stated rather than inherited from a side effect.
			Class<?> initialized = Class.forName(program.getName(), true, program.getClassLoader());
			handler = (RontoHttpServer.Handler) initialized.getDeclaredConstructor().newInstance();
		}
		catch (ExceptionInInitializerError | ReflectiveOperationException ex) {
			throw new ServletException("cannot start rontolisp program " + program.getName() + ": "
					+ (ex.getCause() == null ? ex : ex.getCause()), ex);
		}
		requireRegisteredHandler(program);
		boolean async = !"false".equals(context.getInitParameter("rontolisp.async"));
		ServletRegistration.Dynamic registration = context.addServlet("rontolisp",
				new RontoHttpServlet(handler, async, servletInfo(context)));
		// The top level already ran above, which is what load-on-startup would have been
		// for; set it for the servlet itself anyway, it costs a line.
		registration.setLoadOnStartup(1);
		// NOT the default for a programmatic registration: without it every request dies
		// with "A filter or servlet of the current chain does not support asynchronous
		// operations".
		registration.setAsyncSupported(true);
		registration.addMapping("/*");
	}

	/**
	 * How long a handler registration still in flight on a thread the top level spawned
	 * is given to land. Six orders of magnitude above what it takes in practice: the
	 * thread is already runnable when this is reached, and only a war that never
	 * registers ever spends the whole budget -- a failed deployment, where the wait costs
	 * nothing.
	 */
	private static final long REGISTRATION_WAIT_NANOS = 5_000_000_000L;

	/**
	 * Fails the deployment when the top level ran and still left the handler slot empty.
	 * Without this every request would 500 with an unattributable
	 * {@code NullPointerException} instead.
	 *
	 * <p>
	 * The wait is what makes {@code clack:clackup} work at its DEFAULT
	 * {@code :use-thread t}: that asks {@code clack.handler:run} to call the handler
	 * backend's {@code run} on a spawned thread, and the JVM holds that thread at the
	 * class-initialization lock until {@code <clinit>} returns -- so the registration
	 * provably cannot land before this point, and observing an empty slot the instant
	 * initialization finishes means nothing yet. Without the wait the deployment succeeds
	 * or fails by coin flip (measured: 3 of 10). The slot is a VOLATILE field
	 * ({@code JvmLispCompiler}), which is what makes the value this loop reads a
	 * published one rather than a lucky one.
	 *
	 * <p>
	 * What is left after the budget is the shape this check was written for: a class
	 * whose top level lives in {@code main} (a war built without the {@code <clinit>}
	 * move), which never registers at all.
	 */
	private static void requireRegisteredHandler(Class<?> program) throws ServletException {
		final Field slot;
		try {
			slot = program.getDeclaredField("_httpHandlerFn");
		}
		catch (NoSuchFieldException ex) {
			// Not a rontolisp-emitted class shape; nothing to verify.
			return;
		}
		try {
			slot.setAccessible(true);
			long deadline = System.nanoTime() + REGISTRATION_WAIT_NANOS;
			while (slot.get(null) == null) {
				if (System.nanoTime() - deadline >= 0) {
					throw new ServletException("the rontolisp program " + program.getName() + " initialized without"
							+ " registering its handler: its top level did not run at class initialization"
							+ " (was this war compiled by rontolisp's -o app.war?)");
				}
				Thread.sleep(1);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ServletException(
					"interrupted while waiting for the rontolisp program " + program.getName() + " to register", ex);
		}
		catch (ReflectiveOperationException | SecurityException ex) {
			// The slot cannot be read here; a broken program then fails per request
			// instead of at deployment, which is the pre-check behavior.
		}
	}

	/**
	 * What the servlet answers for {@code getServletInfo()}: the war manifest's
	 * {@code Created-By} line ({@code rontolisp <version>}), so a container's manager
	 * page says what is deployed. This class must not import the project's own
	 * {@code Version} (nothing of the project may travel that was not asked for), so the
	 * version is read back off the artifact it is already stamped into.
	 */
	private static String servletInfo(ServletContext context) {
		try (InputStream manifest = context.getResourceAsStream("/META-INF/MANIFEST.MF")) {
			if (manifest != null) {
				String createdBy = new Manifest(manifest).getMainAttributes().getValue("Created-By");
				if (createdBy != null && !createdBy.isEmpty()) {
					return createdBy;
				}
			}
		}
		catch (IOException ignored) {
			// Fall through to the bare name.
		}
		return "rontolisp";
	}

}
