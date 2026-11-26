package am.ik.servlet.httpserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EventListener;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * A Servlet container on the JDK's HTTP server: one web application at one context path,
 * defined by a builder and started.
 *
 * <pre>{@code
 * ServletHttpServer server = ServletHttpServer.builder()
 *     .port(8080)
 *     .servlet("hello", (request, response) -> response.getWriter().println("Hello!"),
 *             servlet -> servlet.urlPatterns("/hello"))
 *     .start();
 * }</pre>
 *
 * Where the server listens comes first ({@link #builder()}); the rest is optional and in
 * any order. {@link Builder#start()} binds the address, registers what the builder was
 * given in its order, then what the application declares where {@link Builder#discover()}
 * asks for it, initializes the application and begins to serve. An application that
 * declares everything by annotations registers nothing:
 *
 * <pre>{@code
 * ServletHttpServer.builder().port(8080).discover().start();
 * }</pre>
 *
 * The application's resources are the files of the class path under
 * {@code META-INF/resources}, after those of a {@link Builder#documentRoot document
 * root}; where there are any, a servlet named {@code default} serves them at {@code /}
 * unless the application maps a servlet of its own there.
 *
 * <p>
 * An instance of this class is a server that was started: {@link #stop()} ends it. A
 * {@link Definition} starts a new one each time.
 */
public final class ServletHttpServer implements AutoCloseable {

	private final HttpServer server;

	private final WebContext context;

	private final @Nullable ExecutorService ownExecutor;

	private boolean stopped;

	private ServletHttpServer(HttpServer server, WebContext context, @Nullable ExecutorService ownExecutor) {
		this.server = server;
		this.context = context;
		this.ownExecutor = ownExecutor;
	}

	/**
	 * The first step of a server's definition: where it listens.
	 * @return the step that takes the address
	 */
	public static AddressStep builder() {
		return new AddressStep() {
			@Override
			public Builder port(int port) {
				return new ServerBuilder(new InetSocketAddress(port));
			}

			@Override
			public Builder address(InetSocketAddress address) {
				return new ServerBuilder(Objects.requireNonNull(address, "address"));
			}
		};
	}

	/**
	 * The port the server listens on.
	 * @return the bound port
	 */
	public int port() {
		return this.server.getAddress().getPort();
	}

	/**
	 * Stops at once: the requests in hand are cut.
	 */
	public void stop() {
		stop(0);
	}

	/**
	 * Stops accepting requests, waits for the requests in hand to be answered or for the
	 * delay, then destroys the servlets, the filters and the context. An asynchronous
	 * request still in hand then ends as one that failed, its listeners told.
	 * @param delay the longest wait for the requests in hand, in whole seconds (a
	 * fraction counts as one)
	 */
	public void stop(Duration delay) {
		if (delay.isNegative()) {
			throw new IllegalArgumentException("a delay is not negative: " + delay);
		}
		long seconds = delay.toSeconds() + (delay.getNano() > 0 ? 1 : 0);
		stop((int) Math.min(seconds, Integer.MAX_VALUE));
	}

	private synchronized void stop(int seconds) {
		if (this.stopped) {
			return;
		}
		this.stopped = true;
		// The server waits for the handlers in hand, whatever runs them, up to the delay.
		this.server.stop(seconds);
		// an asynchronous request still in hand ends as one that failed
		this.context.abort();
		ExecutorService own = this.ownExecutor;
		if (own != null) {
			own.shutdown();
		}
		this.context.stop();
	}

	/** Stops at once, as {@link #stop()}. */
	@Override
	public void close() {
		stop();
	}

	/** Where the server listens: the step every definition starts with. */
	public interface AddressStep {

		/**
		 * Listens on a port of every local address.
		 * @param port the port, or 0 for a free one ({@link ServletHttpServer#port()}
		 * tells which)
		 * @return the builder of the rest
		 */
		Builder port(int port);

		/**
		 * Listens on an address.
		 * @param address the address to bind
		 * @return the builder of the rest
		 */
		Builder address(InetSocketAddress address);

	}

	/**
	 * What a server serves and how. Each registration is the Servlet API's own, made when
	 * the server starts, in the order the builder was given them; a name or a URL pattern
	 * taken twice fails the start. The options of a registration are given by a lambda
	 * over a builder of its own, run where the registration is given.
	 */
	public interface Builder {

		/**
		 * The context path of the application: the root context unless given.
		 * @param contextPath empty or {@code "/"} for the root context, else a path
		 * starting with a slash and not ending with one
		 * @return this builder
		 */
		Builder contextPath(String contextPath);

		/**
		 * Registers a servlet.
		 * @param name the servlet's name
		 * @param servlet the servlet, shared by every server a definition starts
		 * @param options its URL patterns and the rest
		 * @return this builder
		 */
		Builder servlet(String name, Servlet servlet, Consumer<ServletOptions> options);

		/**
		 * Registers a servlet of a class, made by its public constructor of no parameter
		 * when the server starts.
		 * @param name the servlet's name
		 * @param servletClass the servlet's class
		 * @param options its URL patterns and the rest
		 * @return this builder
		 */
		Builder servlet(String name, Class<? extends Servlet> servletClass, Consumer<ServletOptions> options);

		/**
		 * Registers a servlet that is a lambda: every request mapped to it, whatever its
		 * method.
		 * @param name the servlet's name
		 * @param handler what answers a request
		 * @param options its URL patterns and the rest
		 * @return this builder
		 */
		Builder servlet(String name, Handler handler, Consumer<ServletOptions> options);

		/**
		 * Registers a filter. The builder's filters are mapped ahead of those the
		 * application declares, in the order they were given.
		 * @param name the filter's name
		 * @param filter the filter, shared by every server a definition starts
		 * @param options its URL patterns or servlet names, and the rest
		 * @return this builder
		 */
		Builder filter(String name, Filter filter, Consumer<FilterOptions> options);

		/**
		 * Registers a filter of a class, made by its public constructor of no parameter
		 * when the server starts.
		 * @param name the filter's name
		 * @param filterClass the filter's class
		 * @param options its URL patterns or servlet names, and the rest
		 * @return this builder
		 */
		Builder filter(String name, Class<? extends Filter> filterClass, Consumer<FilterOptions> options);

		/**
		 * Registers a filter that is a lambda of the request and the response of HTTP and
		 * the chain.
		 * @param name the filter's name
		 * @param filter what filters a request
		 * @param options its URL patterns or servlet names, and the rest
		 * @return this builder
		 */
		Builder filter(String name, FilterHandler filter, Consumer<FilterOptions> options);

		/**
		 * Registers a listener: of the context, its attributes, a request, its
		 * attributes, a session, its attributes or its id.
		 * @param listener the listener, shared by every server a definition starts
		 * @return this builder
		 */
		Builder listener(EventListener listener);

		/**
		 * Registers a listener of a class, made by its public constructor of no parameter
		 * when the server starts.
		 * @param listenerClass the listener's class
		 * @return this builder
		 */
		Builder listener(Class<? extends EventListener> listenerClass);

		/**
		 * Runs an action once the application starts, among the context's listeners in
		 * the order the builder was given them: what it registers through the context is
		 * the application's.
		 * @param action what is done with the context
		 * @return this builder
		 */
		Builder onStartup(Consumer<ServletContext> action);

		/**
		 * Runs an action once the application stops, among the context's listeners in the
		 * reverse of the order the builder was given them.
		 * @param action what is done with the context
		 * @return this builder
		 */
		Builder onShutdown(Consumer<ServletContext> action);

		/**
		 * Registers what the application declares, after what the builder was given: the
		 * classes that carry {@code @WebServlet}, {@code @WebFilter} or
		 * {@code @WebListener}, in the order of their names, then what each
		 * {@code ServletContainerInitializer} the class loader's services name registers.
		 * The classes are those of the class path, and those a resource
		 * {@code META-INF/annotations/<annotation type>} lists, a binary name a line.
		 * @return this builder
		 */
		Builder discover();

		/**
		 * Sets a directory whose files are the application's resources, ahead of those
		 * under {@code META-INF/resources} of the class path: what {@code getResource}
		 * and {@code getRealPath} of the context answer, and what the default servlet
		 * serves.
		 * @param directory the application's document root
		 * @return this builder
		 */
		Builder documentRoot(Path directory);

		/**
		 * Sets how the application's sessions are kept and tracked.
		 * @param options the store, the timeout, the cookie
		 * @return this builder
		 */
		Builder sessions(Consumer<SessionOptions> options);

		/**
		 * Answers an error of a status by a page of the application: what
		 * {@code sendError} asks for, and what the container answers. The page is
		 * dispatched to as an {@code ERROR} dispatch, the request telling the error by
		 * the {@code jakarta.servlet.error.*} attributes.
		 * @param status the status, 400 to 599
		 * @param location the page's path inside the context, starting with a slash
		 * @return this builder
		 */
		Builder errorPage(int status, String location);

		/**
		 * Answers an exception a servlet or a filter throws by a page of the application:
		 * the page of the nearest class of the exception, else of the cause of a
		 * {@code ServletException}, else the page of status 500.
		 * @param type the exception's class and its subclasses
		 * @param location the page's path inside the context, starting with a slash
		 * @return this builder
		 */
		Builder errorPage(Class<? extends Throwable> type, String location);

		/**
		 * Answers an error no other error page answers by a page of the application.
		 * @param location the page's path inside the context, starting with a slash
		 * @return this builder
		 */
		Builder errorPage(String location);

		/**
		 * The largest form a request's parameters are read from: 2 MiB unless given. A
		 * longer one is an {@code IllegalStateException} of the parameters.
		 * @param bytes the octets of the body, a negative number for no limit
		 * @return this builder
		 */
		Builder maxFormSize(int bytes);

		/**
		 * Where {@code ServletContext.log} writes, and what the container logs: the
		 * standard error unless given.
		 * @param log what takes a message and the exception it tells of, or null
		 * @return this builder
		 */
		Builder log(BiConsumer<String, @Nullable Throwable> log);

		/**
		 * Serves HTTPS: the requests are {@code https}, secure, and of port 443 unless
		 * their {@code Host} names another.
		 * @param sslContext the server's keys and what it trusts
		 * @return this builder
		 */
		Builder https(SSLContext sslContext);

		/**
		 * Sets what runs the requests. Without one the server runs each request on a
		 * virtual thread of an executor of its own, which it shuts down when stopped.
		 * @param executor what runs a request, left running when the server stops
		 * @return this builder
		 */
		Builder executor(Executor executor);

		/**
		 * The server as defined so far, which this builder no longer changes.
		 * @return the definition
		 */
		Definition build();

		/**
		 * Starts the server as defined so far.
		 * @return the started server
		 * @throws IOException if the address cannot be bound
		 * @throws ServletException if what the application is given or declares cannot be
		 * registered, or a filter or a servlet refuses to initialize
		 * @see Definition#start()
		 */
		ServletHttpServer start() throws IOException, ServletException;

	}

	/** The options of a servlet's registration. */
	public interface ServletOptions {

		/**
		 * Maps URL patterns to the servlet: an exact path, {@code /prefix/*},
		 * {@code *.extension}, {@code /} (the default) or empty (the context root).
		 * @param urlPatterns the patterns
		 * @return these options
		 */
		ServletOptions urlPatterns(String... urlPatterns);

		/**
		 * Initializes the servlet when the server starts, the lower number first; without
		 * it the servlet is initialized by its first request.
		 * @param order zero or more
		 * @return these options
		 */
		ServletOptions loadOnStartup(int order);

		/**
		 * Lets a request the servlet serves call {@code startAsync}.
		 * @return these options
		 */
		ServletOptions asyncSupported();

		/**
		 * Gives the servlet an initialization parameter.
		 * @param name the name, given once
		 * @param value the value
		 * @return these options
		 */
		ServletOptions initParameter(String name, String value);

		/**
		 * Lets the servlet read a {@code multipart/form-data} body as parts.
		 * @param config where the parts go and how large they are
		 * @return these options
		 */
		ServletOptions multipartConfig(MultipartConfigElement config);

	}

	/** The options of a filter's registration. */
	public interface FilterOptions {

		/**
		 * Applies the filter to the paths of URL patterns.
		 * @param urlPatterns the patterns
		 * @return these options
		 */
		FilterOptions urlPatterns(String... urlPatterns);

		/**
		 * Applies the filter to the servlets of names, {@code *} for every servlet.
		 * @param servletNames the names
		 * @return these options
		 */
		FilterOptions servletNames(String... servletNames);

		/**
		 * The dispatches the filter takes part in: {@code REQUEST} unless given.
		 * @param dispatcherTypes the kinds of dispatch
		 * @return these options
		 */
		FilterOptions dispatcherTypes(DispatcherType... dispatcherTypes);

		/**
		 * Lets a request the filter is applied to call {@code startAsync}.
		 * @return these options
		 */
		FilterOptions asyncSupported();

		/**
		 * Gives the filter an initialization parameter.
		 * @param name the name, given once
		 * @param value the value
		 * @return these options
		 */
		FilterOptions initParameter(String name, String value);

	}

	/** How the application's sessions are kept and tracked. */
	public interface SessionOptions {

		/**
		 * Keeps the sessions in a store. Without one they are kept in the first store the
		 * class loader's service providers of {@link SessionStore} name, else in the
		 * server's memory, where they last as long as it does.
		 * @param store the store, asked by every request that has a session
		 * @return these options
		 */
		SessionOptions store(SessionStore store);

		/**
		 * How long a session lasts with no request: 30 minutes unless given.
		 * @param timeout whole minutes, zero for a session that never expires
		 * @return these options
		 */
		SessionOptions timeout(Duration timeout);

		/**
		 * How a request names its session: by the cookie unless given.
		 * @param trackingModes {@code COOKIE}, {@code URL} or both
		 * @return these options
		 */
		SessionOptions trackingModes(SessionTrackingMode... trackingModes);

		/**
		 * The cookie a session is tracked by.
		 * @param options its name and its attributes
		 * @return these options
		 */
		SessionOptions cookie(Consumer<CookieOptions> options);

	}

	/**
	 * The cookie a session is tracked by: {@code JSESSIONID}, {@code HttpOnly}, the path
	 * the context's, unless given.
	 */
	public interface CookieOptions {

		/**
		 * The cookie's name: {@code JSESSIONID} unless given.
		 * @param name a token
		 * @return these options
		 */
		CookieOptions name(String name);

		/**
		 * The {@code Domain} attribute: none unless given.
		 * @param domain the domain
		 * @return these options
		 */
		CookieOptions domain(String domain);

		/**
		 * The {@code Path} attribute: the context path unless given.
		 * @param path the path
		 * @return these options
		 */
		CookieOptions path(String path);

		/**
		 * Whether the cookie is {@code HttpOnly}: it is unless given.
		 * @param httpOnly whether a script of the page cannot read it
		 * @return these options
		 */
		CookieOptions httpOnly(boolean httpOnly);

		/**
		 * Whether the cookie is {@code Secure}: it is not unless given.
		 * @param secure whether it is sent over HTTPS alone
		 * @return these options
		 */
		CookieOptions secure(boolean secure);

		/**
		 * The {@code Max-Age} attribute: none unless given, so the cookie lasts as long
		 * as the browser.
		 * @param maxAge whole seconds
		 * @return these options
		 */
		CookieOptions maxAge(Duration maxAge);

		/**
		 * An attribute of the cookie ({@code SameSite} ..).
		 * @param name the attribute's name, a token
		 * @param value its value, empty for a flag
		 * @return these options
		 */
		CookieOptions attribute(String name, String value);

	}

	/**
	 * A servlet as a lambda: what answers a request, the servlet's {@code service}.
	 */
	@FunctionalInterface
	public interface Handler {

		/**
		 * Answers a request.
		 * @param request the request
		 * @param response its response
		 * @throws IOException if the request cannot be read or the response written
		 * @throws ServletException if the request cannot be answered
		 */
		void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

	}

	/**
	 * A filter as a lambda of the request and the response of HTTP, as {@code HttpFilter}
	 * is a class.
	 */
	@FunctionalInterface
	public interface FilterHandler extends Filter {

		/**
		 * Filters a request.
		 * @param request the request
		 * @param response its response
		 * @param chain what is next: {@code chain.doFilter(request, response)} hands the
		 * request on
		 * @throws IOException if the request cannot be read or the response written
		 * @throws ServletException if the request cannot be answered
		 */
		void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws IOException, ServletException;

		@Override
		default void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			doFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
		}

	}

	/**
	 * A server defined, which nothing changes: each {@link #start()} starts a new server
	 * of it. An instance it was given is shared by every server it starts.
	 */
	public static final class Definition {

		/** The JDK server's switch of TCP_NODELAY on the connections it accepts. */
		private static final String NODELAY = "sun.net.httpserver.nodelay";

		/**
		 * The connections that may wait to be accepted. The JDK's default (50) made 2,000
		 * connections at once wait 2 to 3.7 s for the clients' SYN resent, as Tomcat's
		 * (100) and Jetty's (the system's) do; this one, 0.4 s. The system caps it at its
		 * own limit.
		 */
		static final int BACKLOG = 1024;

		private final InetSocketAddress address;

		private final String contextPath;

		private final @Nullable Executor executor;

		private final List<Registration> registrations;

		private final @Nullable Registration discovery;

		private final @Nullable Binding secure;

		Definition(InetSocketAddress address, String contextPath, @Nullable Executor executor,
				List<Registration> registrations, @Nullable Registration discovery, @Nullable Binding secure) {
			this.address = address;
			this.contextPath = contextPath;
			this.executor = executor;
			this.registrations = registrations;
			this.discovery = discovery;
			this.secure = secure;
		}

		/**
		 * Binds the address, registers what the server was given, then what the
		 * application declares where it was asked to be discovered, initializes the
		 * application (its listeners, its filters, then the servlets loaded on startup)
		 * and begins to serve. What fails the start is thrown after what was initialized
		 * is destroyed and the address released.
		 * @return the started server
		 * @throws IOException if the address cannot be bound
		 * @throws ServletException if what the application is given or declares cannot be
		 * registered, or a filter or a servlet refuses to initialize
		 */
		public ServletHttpServer start() throws IOException, ServletException {
			// The application's classes are the ones the thread starting the server
			// finds.
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			WebContext context = new WebContext(this.contextPath,
					loader != null ? loader : ServletHttpServer.class.getClassLoader());
			if (System.getProperty("java.class.path") != null) {
				// A JDK's server: without TCP_NODELAY a response of more than one write
				// waits 40 ms for the client's delayed acknowledgement. Read when its
				// first server is made; a property the application set is kept. A
				// program compiled ahead of time has no such property and holds none of
				// this.
				if (System.getProperty(NODELAY) == null) {
					System.setProperty(NODELAY, "true");
				}
			}
			// An HTTPS server is made by a function the builder's https made: a program
			// that serves HTTP names no class of TLS.
			Binding secure = this.secure;
			HttpServer bound = secure != null ? secure.bind(this.address) : HttpServer.create(this.address, BACKLOG);
			context.secure(secure != null);
			try {
				for (Registration registration : this.registrations) {
					registration.register(context);
				}
				Registration discovery = this.discovery;
				if (discovery != null) {
					discovery.register(context);
				}
				context.start();
			}
			catch (ServletException | RuntimeException ex) {
				bound.stop(0);
				context.stop();
				throw ex;
			}
			String path = context.getContextPath();
			bound.createContext(path.isEmpty() ? "/" : path, context::handle);
			ExecutorService own = null;
			Executor runs = this.executor;
			if (runs == null) {
				// A request may wait on its thread for what the application waits for: a
				// virtual thread waits for next to nothing.
				own = Executors.newVirtualThreadPerTaskExecutor();
				runs = own;
			}
			bound.setExecutor(runs);
			context.executor(runs);
			bound.start();
			return new ServletHttpServer(bound, context, own);
		}

	}

	/** Binds a server of another kind than HTTP's. */
	@FunctionalInterface
	interface Binding {

		HttpServer bind(InetSocketAddress address) throws IOException;

	}

	/** A part of a definition, made of the context when a server starts. */
	@FunctionalInterface
	interface Registration {

		void register(WebContext context) throws ServletException;

	}

}
