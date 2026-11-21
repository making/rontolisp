package am.ik.servlet.httpserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

/**
 * A Servlet container on the JDK's HTTP server: one web application at one context path.
 *
 * <pre>{@code
 * ServletHttpServer server = new ServletHttpServer(8080);
 * server.getServletContext().addServlet("hello", new HelloServlet()).addMapping("/hello");
 * server.start();
 * }</pre>
 *
 * Servlets, filters and listeners are registered through {@link #getServletContext()}
 * before {@link #start()}, which binds the port, initializes the application and begins
 * to serve. {@link #stop()} ends the serving and destroys the application; a stopped
 * server is not started again.
 */
public final class ServletHttpServer implements AutoCloseable {

	private final InetSocketAddress address;

	private final WebContext context;

	private Executor executor;

	private ExecutorService ownExecutor;

	private HttpServer server;

	private boolean stopped;

	/**
	 * A server of the root context on a port of every local address.
	 * @param port the port, or 0 for a free one ({@link #getPort()} tells which)
	 */
	public ServletHttpServer(int port) {
		this(port, "");
	}

	/**
	 * A server of one context on a port of every local address.
	 * @param port the port, or 0 for a free one
	 * @param contextPath the context path: empty for the root context, else a path
	 * starting with a slash and not ending with one
	 */
	public ServletHttpServer(int port, String contextPath) {
		this(new InetSocketAddress(port), contextPath);
	}

	/**
	 * A server of one context on an address.
	 * @param address the address to bind
	 * @param contextPath the context path: empty for the root context, else a path
	 * starting with a slash and not ending with one
	 */
	public ServletHttpServer(InetSocketAddress address, String contextPath) {
		if (address == null) {
			throw new NullPointerException("address");
		}
		if (contextPath == null || contextPath.equals("/")) {
			contextPath = "";
		}
		if (!contextPath.isEmpty() && (!contextPath.startsWith("/") || contextPath.endsWith("/"))) {
			throw new IllegalArgumentException(
					"a context path starts with a slash and does not end with one: " + contextPath);
		}
		this.address = address;
		this.context = new WebContext(contextPath);
	}

	/**
	 * The context of the application, where its servlets, filters and listeners are
	 * registered until the server starts.
	 * @return the one context of this server
	 */
	public ServletContext getServletContext() {
		return this.context;
	}

	/**
	 * Sets what runs the requests. Without one the server runs each request on a thread
	 * of a pool of its own, which it shuts down when stopped.
	 * @param executor what runs a request, left running when the server stops
	 */
	public synchronized void setExecutor(Executor executor) {
		if (this.server != null) {
			throw new IllegalStateException("the server has been started");
		}
		this.executor = executor;
	}

	/**
	 * Binds the address, initializes the application (its listeners, its filters, then
	 * the servlets loaded on startup) and begins to serve.
	 * @throws IOException if the address cannot be bound
	 * @throws ServletException if a filter or a servlet refuses to initialize
	 */
	public synchronized void start() throws IOException, ServletException {
		if (this.server != null || this.stopped) {
			throw new IllegalStateException("the server has been started");
		}
		HttpServer bound = HttpServer.create(this.address, 0);
		try {
			this.context.start();
		}
		catch (ServletException | RuntimeException ex) {
			bound.stop(0);
			this.context.stop();
			this.stopped = true;
			throw ex;
		}
		String contextPath = this.context.getContextPath();
		bound.createContext(contextPath.isEmpty() ? "/" : contextPath, this.context::handle);
		Executor runs = this.executor;
		if (runs == null) {
			this.ownExecutor = Executors.newCachedThreadPool();
			runs = this.ownExecutor;
		}
		bound.setExecutor(runs);
		this.server = bound;
		bound.start();
	}

	/**
	 * The port the server listens on.
	 * @return the bound port
	 */
	public synchronized int getPort() {
		if (this.server == null) {
			throw new IllegalStateException("the server has not been started");
		}
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
	 * delay, then destroys the servlets, the filters and the context.
	 * @param delaySeconds the longest wait for the requests in hand
	 */
	public synchronized void stop(int delaySeconds) {
		if (this.server == null || this.stopped) {
			return;
		}
		this.stopped = true;
		this.server.stop(delaySeconds);
		if (this.ownExecutor != null) {
			this.ownExecutor.shutdown();
			try {
				// A request the server no longer waits for may still be in its servlet.
				this.ownExecutor.awaitTermination(delaySeconds, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		this.context.stop();
	}

	@Override
	public void close() {
		stop();
	}

}
