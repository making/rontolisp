package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SPIKE: adapts a compiled rontolisp http-handler program class to the Servlet API.
 *
 * <p>
 * In ASYNC mode the container thread is released with {@code startAsync} and the whole
 * blocking Lisp pipeline runs on a fresh virtual thread -- which is what
 * {@code RontoHttpServer} already does for the JDK transport, and what
 * {@code .kb/concurrent-served-requests.md} requires.
 */
public final class RontoHttpServlet extends HttpServlet {

	private final RontoHttpServer.Handler handler;

	private final boolean async;

	private transient ExecutorService requestThreads;

	public RontoHttpServlet(RontoHttpServer.Handler handler, boolean async) {
		this.handler = handler;
		this.async = async;
	}

	@Override
	public void init() {
		if (this.async) {
			this.requestThreads = Executors.newVirtualThreadPerTaskExecutor();
		}
	}

	@Override
	public void destroy() {
		if (this.requestThreads != null) {
			this.requestThreads.shutdown();
		}
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
		if (!this.async) {
			write(this.handler.handle(toRequest(req)), res);
			return;
		}
		AsyncContext context = req.startAsync();
		// The container's default (30s on Tomcat) would kill a slow handler mid-flight;
		// the JDK transport imposes no deadline, so neither does this one.
		context.setTimeout(0);
		this.requestThreads.execute(() -> {
			try {
				// Read the request and run the handler on THIS thread, so the container
				// thread is already back in the pool.
				RontoHttpServer.Response response = this.handler.handle(toRequest(req));
				write(response, (HttpServletResponse) context.getResponse());
			}
			catch (Throwable ex) {
				// A handler that dies must not hang the request until the timeout.
				System.err.println("HTTP-HANDLER: handler failed: " + ex);
				try {
					HttpServletResponse failed = (HttpServletResponse) context.getResponse();
					if (!failed.isCommitted()) {
						failed.reset();
						failed.setStatus(500);
						failed.setContentType("text/plain");
						failed.getOutputStream().write("Internal Server Error".getBytes());
					}
				}
				catch (IOException _) {
					// The peer is gone; nothing left to report to.
				}
			}
			finally {
				context.complete();
			}
		});
	}

	private static void write(RontoHttpServer.Response response, HttpServletResponse res) throws IOException {
		res.setStatus(response.status());
		for (RontoHttpServer.Header header : response.headers()) {
			res.addHeader(header.name(), header.value());
		}
		byte[] body = response.body();
		if (body.length > 0) {
			try (OutputStream out = res.getOutputStream()) {
				out.write(body);
			}
		}
	}

	private static RontoHttpServer.Request toRequest(HttpServletRequest req) throws IOException {
		List<RontoHttpServer.Header> headers = new ArrayList<>();
		for (String name : Collections.list(req.getHeaderNames())) {
			for (Enumeration<String> values = req.getHeaders(name); values.hasMoreElements();) {
				headers.add(new RontoHttpServer.Header(name, values.nextElement()));
			}
		}
		String target = req.getRequestURI();
		if (req.getQueryString() != null) {
			target = target + "?" + req.getQueryString();
		}
		byte[] body = req.getInputStream().readAllBytes();
		return new RontoHttpServer.Request(req.getMethod(), target, headers, body, req.getProtocol(), req.getScheme(),
				req.getLocalName() == null ? "" : req.getLocalName(), req.getLocalPort(),
				req.getRemoteAddr() == null ? "" : req.getRemoteAddr(), req.getRemotePort());
	}

}
