package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The Servlet transport of a compiled {@code rontolisp:http-handler} program: fills a
 * {@link RontoHttpServer.Request} in from the container's request, runs the program's
 * {@link RontoHttpServer.Handler} and writes the {@link RontoHttpServer.Response} back.
 * Registered by {@link RontoHttpServletInitializer}; nothing else constructs it.
 *
 * <p>
 * It is ASYNC by default, and that is a correctness requirement rather than a tuning
 * choice: {@code .kb/concurrent-served-requests.md}'s invariant is one virtual thread per
 * request, and a container pool hands the SAME platform thread to request after request
 * while the compiled class keeps per-request state in ThreadLocals. {@code startAsync}
 * releases the container thread and the whole existing blocking pipeline (read the body,
 * {@code handle}, write the response) runs on a fresh virtual thread -- the same shape
 * {@code RontoHttpServer} gives the JDK transport. The synchronous path stays as an
 * opt-out (a {@code rontolisp.async} context parameter of {@code false}) for a container
 * already configured with virtual threads, and as the automatic fallback when a filter in
 * the chain does not declare async support and {@code startAsync} therefore throws -- a
 * war that degrades in throughput beats a war that 500s because someone added a logging
 * filter.
 *
 * <p>
 * Like everything in this package the class travels INSIDE the emitted artifact (the
 * war's {@code WEB-INF/classes}), so it imports nothing of the project. Its
 * {@code jakarta.servlet} import is the one sanctioned exception to this package
 * importing only {@code java.base} ({@code .kb/jvm-export.md}, "What travels"): the class
 * is emitted into a {@code .war} alone, and a servlet container that has no
 * {@code jakarta.servlet} is not a container.
 */
public final class RontoHttpServlet extends HttpServlet {

	private final RontoHttpServer.Handler handler;

	private final boolean async;

	/** What {@link #getServletInfo()} answers -- the war manifest's Created-By line. */
	private final String info;

	private final AtomicBoolean warnedSyncFallback = new AtomicBoolean();

	// Created unconditionally (a virtual-thread-per-task executor holds no threads of
	// its own, so the sync mode pays nothing for it): this package cannot spell a
	// nullable field -- the build's @Nullable is RuntimeVisible and would follow the
	// class into the war (.kb/jvm-export.md).
	private final ExecutorService requestThreads = Executors.newVirtualThreadPerTaskExecutor();

	RontoHttpServlet(RontoHttpServer.Handler handler, boolean async, String info) {
		this.handler = handler;
		this.async = async;
		this.info = info;
	}

	@Override
	public String getServletInfo() {
		return this.info;
	}

	@Override
	public void destroy() {
		// The war holds no port; the executor is the only resource to release.
		this.requestThreads.shutdown();
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
		if (!this.async) {
			serve(req, res);
			return;
		}
		final AsyncContext context;
		try {
			context = req.startAsync();
		}
		catch (IllegalStateException ex) {
			// A filter without <async-supported>true</async-supported> anywhere in the
			// chain makes startAsync throw. Serve the request anyway, on the container's
			// thread, and say ONCE how to stop paying for the attempt.
			if (this.warnedSyncFallback.compareAndSet(false, true)) {
				System.err.println("HTTP-HANDLER: a filter in the chain does not support asynchronous operations;"
						+ " serving synchronously. Set the rontolisp.async context parameter to false to make"
						+ " this the configured mode (" + ex.getMessage() + ")");
			}
			serve(req, res);
			return;
		}
		// Tomcat's default AsyncContext timeout is 30 s and would kill a slow handler
		// mid-flight; the JDK transport imposes no deadline, so neither does this one.
		context.setTimeout(0);
		this.requestThreads.execute(() -> {
			try {
				// Read the body and run the handler on THIS thread, so the container
				// thread is already back in its pool -- reading before startAsync would
				// keep it paying for the read.
				serve(req, (HttpServletResponse) context.getResponse());
			}
			catch (Throwable ex) {
				fail(context, ex);
			}
			finally {
				// A handler that throws must not leave the request hanging until the
				// async timeout.
				context.complete();
			}
		});
	}

	private void serve(HttpServletRequest req, HttpServletResponse res) throws IOException {
		final RontoHttpServer.Response response;
		try {
			response = this.handler.handle(toRequest(req));
		}
		catch (RuntimeException ex) {
			// A handler that dies must not vanish: the same one-line report the JDK
			// transport's dispatch prints for the same reason.
			System.err.println("HTTP-HANDLER: handler failed: " + ex);
			write(RontoHttpServer.Response.of(500, List.of(new RontoHttpServer.Header("content-type", "text/plain")),
					"Internal Server Error"), res);
			return;
		}
		write(response, res);
	}

	private static void fail(AsyncContext context, Throwable ex) {
		System.err.println("HTTP-HANDLER: handler failed: " + ex);
		try {
			HttpServletResponse failed = (HttpServletResponse) context.getResponse();
			if (!failed.isCommitted()) {
				failed.reset();
				failed.setStatus(500);
				failed.setContentType("text/plain");
				failed.getOutputStream().write("Internal Server Error".getBytes(StandardCharsets.UTF_8));
			}
		}
		catch (IOException ignored) {
			// The peer is gone; nothing left to report to.
		}
	}

	private static void write(RontoHttpServer.Response response, HttpServletResponse res) throws IOException {
		res.setStatus(response.status());
		for (RontoHttpServer.Header header : response.headers()) {
			res.addHeader(header.name(), header.value());
		}
		// The octets exactly as the normalizer answered them -- no encode of this
		// transport's own, the byte-exact rule every wire-writing transport follows
		// (.kb/http-server.md).
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
		// The target verbatim: still percent-encoded, query included -- the raw
		// transport fact Request declares, decoded once by http-server.lisp.
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
