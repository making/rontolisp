package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SPIKE: adapts a compiled rontolisp http-handler program class -- which implements
 * {@link RontoHttpServer.Handler} -- to the Servlet API, so the same handler serves
 * inside a war instead of behind the embedded JDK HttpServer.
 */
public final class RontoHttpServlet extends HttpServlet {

	private final RontoHttpServer.Handler handler;

	public RontoHttpServlet(RontoHttpServer.Handler handler) {
		this.handler = handler;
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
		RontoHttpServer.Response response = this.handler.handle(toRequest(req));
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
