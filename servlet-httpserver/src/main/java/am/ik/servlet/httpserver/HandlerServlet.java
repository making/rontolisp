package am.ik.servlet.httpserver;

import java.io.IOException;

import am.ik.servlet.httpserver.ServletHttpServer.Handler;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A servlet whose service is a lambda: every request mapped to it, whatever its method.
 */
final class HandlerServlet extends GenericServlet {

	private static final long serialVersionUID = 1L;

	private final transient Handler handler;

	HandlerServlet(Handler handler) {
		this.handler = handler;
	}

	@Override
	public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
		this.handler.handle((HttpServletRequest) request, (HttpServletResponse) response);
	}

}
