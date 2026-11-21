package am.ik.servlet.httpserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The application every container is given: servlets that answer what the container told
 * them, one family of behaviour a servlet.
 */
final class Application {

	/** What the container did to the application, in order. */
	final List<String> events = new CopyOnWriteArrayList<>();

	private final CyclicBarrier together = new CyclicBarrier(2);

	interface Handler {

		void handle(HttpServletRequest request, HttpServletResponse response) throws Exception;

	}

	private final class Scripted extends HttpServlet {

		private final String name;

		private final transient Handler handler;

		Scripted(String name, Handler handler) {
			this.name = name;
			this.handler = handler;
		}

		@Override
		public void init(ServletConfig config) throws ServletException {
			super.init(config);
			Application.this.events.add("init " + this.name + " " + config.getInitParameter("greeting"));
		}

		@Override
		public void destroy() {
			Application.this.events.add("destroy " + this.name);
		}

		@Override
		protected void service(HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {
			try {
				this.handler.handle(request, response);
			}
			catch (ServletException | IOException | RuntimeException ex) {
				throw ex;
			}
			catch (Exception ex) {
				throw new ServletException(ex);
			}
		}

	}

	private final class Marking implements Filter {

		private final String name;

		Marking(String name) {
			this.name = name;
		}

		@Override
		public void init(FilterConfig config) {
			Application.this.events
				.add("init filter " + config.getFilterName() + " " + config.getInitParameter("mark"));
		}

		@Override
		public void destroy() {
			Application.this.events.add("destroy filter " + this.name);
		}

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			if (this.name.equals(request.getParameter("stop"))) {
				response.getWriter().print("stopped by " + this.name);
				return;
			}
			((HttpServletResponse) response).addHeader("X-Filters", this.name);
			response.getWriter().print("<" + this.name + ":" + request.getDispatcherType() + ">");
			ServletRequest passed = request;
			if (this.name.equals("wrapping")) {
				passed = new HttpServletRequestWrapper((HttpServletRequest) request) {
					@Override
					public String getParameter(String name) {
						return name.equals("wrapped") ? "yes" : super.getParameter(name);
					}
				};
			}
			chain.doFilter(passed, response);
			response.getWriter().print("</" + this.name + ">");
		}

	}

	private ServletRegistration.Dynamic servlet(ServletContext context, String name, Handler handler,
			String... patterns) {
		ServletRegistration.Dynamic registration = context.addServlet(name, new Scripted(name, handler));
		if (patterns.length > 0 && !registration.addMapping(patterns).isEmpty()) {
			throw new IllegalStateException("a pattern of " + name + " is taken");
		}
		return registration;
	}

	private FilterRegistration.Dynamic filter(ServletContext context, String name) {
		return context.addFilter(name, new Marking(name));
	}

	/**
	 * The application at the root context: no default servlet, so a path can be unmapped.
	 */
	void registerRoot(ServletContext context) {
		servlet(context, "paths", Application::paths, "/exact", "/prefix/*", "*.ext", "");
	}

	/** The application at a context path. */
	void register(ServletContext context) {
		context.setInitParameter("application", "probe");
		context.setAttribute("made", "before the start");
		context.addListener(new ServletContextListener() {
			@Override
			public void contextInitialized(ServletContextEvent event) {
				Application.this.events
					.add("context initialized " + event.getServletContext().getInitParameter("application"));
			}

			@Override
			public void contextDestroyed(ServletContextEvent event) {
				Application.this.events.add("context destroyed");
			}
		});
		context.addListener(new ServletRequestListener() {
			@Override
			public void requestInitialized(ServletRequestEvent event) {
				HttpServletRequest request = (HttpServletRequest) event.getServletRequest();
				if (request.getRequestURI().endsWith("/listened")) {
					request.setAttribute("heard", "initialized");
				}
			}

			@Override
			public void requestDestroyed(ServletRequestEvent event) {
				HttpServletRequest request = (HttpServletRequest) event.getServletRequest();
				if (request.getRequestURI().endsWith("/listened")) {
					Application.this.events.add("request destroyed " + request.getAttribute("heard"));
				}
			}
		});
		servlet(context, "paths", Application::paths, "/exact", "/prefix/*", "*.ext", "");
		servlet(context, "default", Application::paths, "/");
		servlet(context, "request", this::request, "/request/*");
		servlet(context, "parameters", Application::parameters, "/parameters");
		servlet(context, "body", Application::body, "/body");
		servlet(context, "response", Application::response, "/response");
		servlet(context, "errors", Application::errors, "/errors");
		servlet(context, "dispatch", Application::dispatch, "/dispatch", "/dispatch/deeper/*");
		servlet(context, "target", Application::target, "/target/*", "/filtered/*");
		servlet(context, "together", this::together, "/together");
		servlet(context, "unavailable", (request, response) -> {
			response.setHeader("X-Before", "1");
			throw new UnavailableException("gone");
		}, "/unavailable");
		servlet(context, "busy", (request, response) -> {
			response.setHeader("X-Before", "1");
			throw new UnavailableException("busy", 5);
		}, "/busy");
		servlet(context, "listened", (request, response) -> response.getWriter().print(request.getAttribute("heard")),
				"/listened");
		servlet(context, "events", (request, response) -> response.getWriter().print(this.events), "/events");
		servlet(context, "second", (request, response) -> response.getWriter().print("second"), "/second")
			.setLoadOnStartup(2);
		ServletRegistration.Dynamic first = servlet(context, "first",
				(request, response) -> response.getWriter().print("first"), "/first");
		first.setLoadOnStartup(1);
		first.setInitParameter("greeting", "hello");

		// The order of a chain: the mappings by URL pattern in the order they were added
		// (one added "not after" goes before the ones "after"), then the ones by name.
		FilterRegistration.Dynamic named = filter(context, "named");
		named.addMappingForServletNames(null, false, "target");
		filter(context, "after").addMappingForUrlPatterns(null, true, "/filtered/*");
		FilterRegistration.Dynamic url = filter(context, "url");
		url.setInitParameter("mark", "x");
		url.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/filtered/*");
		filter(context, "wrapping").addMappingForUrlPatterns(null, false, "/filtered/wrapped");
		filter(context, "forwarded").addMappingForUrlPatterns(EnumSet.of(DispatcherType.FORWARD), false,
				"/target/forwarded");
		filter(context, "included").addMappingForUrlPatterns(EnumSet.of(DispatcherType.INCLUDE), false,
				"/target/included");
	}

	private static String mapping(HttpServletMapping mapping) {
		return mapping == null ? null : mapping.getMappingMatch() + "[" + mapping.getMatchValue() + "]["
				+ mapping.getPattern() + "][" + mapping.getServletName() + "]";
	}

	private static void paths(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.getWriter()
			.print("uri=" + request.getRequestURI() + " context=" + request.getContextPath() + " servlet="
					+ request.getServletPath() + " info=" + request.getPathInfo() + " query=" + request.getQueryString()
					+ " mapping=" + mapping(request.getHttpServletMapping()));
	}

	private void request(HttpServletRequest request, HttpServletResponse response) throws IOException {
		PrintWriter out = response.getWriter();
		out.println("method=" + request.getMethod());
		out.println("protocol=" + request.getProtocol());
		out.println("scheme=" + request.getScheme() + " secure=" + request.isSecure());
		out.println("server=" + request.getServerName() + ":" + (request.getServerPort() == request.getLocalPort()
				? "local" : Integer.toString(request.getServerPort())));
		out.println("url=" + request.getRequestURL().toString().replace(":" + request.getLocalPort(), ":local"));
		out.println("remote=" + request.getRemoteAddr() + " " + (request.getRemotePort() > 0));
		out.println("local=" + request.getLocalAddr());
		out.println("header=" + request.getHeader("x-one") + " " + Collections.list(request.getHeaders("X-MANY")) + " "
				+ request.getHeader("X-None") + " " + Collections.list(request.getHeaders("X-None")));
		List<String> names = new ArrayList<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			names.add(name.toLowerCase(Locale.ROOT));
		}
		out.println("names=" + new TreeSet<>(names));
		out.println("int=" + request.getIntHeader("X-Int") + " " + request.getIntHeader("X-None"));
		out.println("date=" + request.getDateHeader("If-Modified-Since") + " " + request.getDateHeader("X-None"));
		List<String> cookies = new ArrayList<>();
		if (request.getCookies() != null) {
			for (Cookie cookie : request.getCookies()) {
				cookies.add(cookie.getName() + "=" + cookie.getValue());
			}
		}
		out.println("cookies=" + (request.getCookies() == null ? null : cookies));
		List<String> locales = new ArrayList<>();
		for (Locale locale : Collections.list(request.getLocales())) {
			locales.add(locale.toLanguageTag());
		}
		out.println("locales="
				+ (request.getHeader("Accept-Language") == null ? request.getLocale().equals(Locale.getDefault())
						: request.getLocale().toLanguageTag() + " " + locales));
		out.println("content=" + request.getContentType() + " " + request.getContentLength() + " "
				+ request.getContentLengthLong() + " " + request.getCharacterEncoding());
		out.println("context=" + request.getServletContext().getContextPath() + " "
				+ request.getServletContext().getAttribute("made") + " "
				+ request.getServletContext().getInitParameter("application") + " "
				+ request.getServletContext().getMajorVersion() + "." + request.getServletContext().getMinorVersion());
		request.setAttribute("a", "1");
		request.setAttribute("b", "2");
		request.removeAttribute("b");
		request.setAttribute("c", null);
		List<String> attributes = Collections.list(request.getAttributeNames());
		attributes.removeIf(name -> !name.matches("[abc]"));
		out.println("attributes=" + attributes + " " + request.getAttribute("a") + " " + request.getAttribute("b"));
		out.println("session=" + request.getSession(false) + " " + request.getRequestedSessionId() + " "
				+ request.isAsyncSupported() + " " + request.isAsyncStarted() + " " + request.getDispatcherType());
	}

	private static void parameters(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setCharacterEncoding("UTF-8");
		String mode = request.getHeader("X-Mode");
		if ("stream-first".equals(mode)) {
			request.getInputStream();
		}
		else if ("set-encoding".equals(mode)) {
			request.setCharacterEncoding("Shift_JIS");
		}
		PrintWriter out = response.getWriter();
		try {
			for (String name : Collections.list(request.getParameterNames())) {
				out.print(name + "=" + Arrays.toString(request.getParameterValues(name)) + ";");
			}
			out.print(" first=" + request.getParameter("a") + " none=" + request.getParameter("none") + " "
					+ Arrays.toString(request.getParameterValues("none")) + " map=" + request.getParameterMap().size());
			if ("then-stream".equals(mode)) {
				out.print(" left=" + request.getInputStream().read());
			}
		}
		catch (IllegalStateException ex) {
			out.print("IllegalStateException");
		}
	}

	private static void body(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setCharacterEncoding("UTF-8");
		PrintWriter out = response.getWriter();
		if ("reader".equals(request.getParameter("as"))) {
			BufferedReader reader = request.getReader();
			StringBuilder text = new StringBuilder();
			for (int c = reader.read(); c >= 0; c = reader.read()) {
				text.append((char) c);
			}
			out.print("read " + text + " again=" + (request.getReader() == reader));
			try {
				request.getInputStream();
				out.print(" stream");
			}
			catch (IllegalStateException ex) {
				out.print(" IllegalStateException");
			}
		}
		else {
			InputStream in = request.getInputStream();
			byte[] octets = in.readAllBytes();
			out.print("read " + octets.length + " octets " + new String(octets, StandardCharsets.ISO_8859_1)
					+ " finished=" + request.getInputStream().isFinished());
			try {
				request.getReader();
				out.print(" reader");
			}
			catch (IllegalStateException ex) {
				out.print(" IllegalStateException");
			}
		}
	}

	private static void response(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String kind = request.getParameter("case");
		switch (kind) {
			case "small":
				response.setContentType("text/plain");
				response.getWriter().print("hello");
				break;
			case "empty":
				break;
			case "large": {
				response.setContentType("application/octet-stream");
				byte[] octets = new byte[20000];
				Arrays.fill(octets, (byte) 'x');
				response.getOutputStream().write(octets, 0, 10000);
				boolean committed = response.isCommitted();
				response.getOutputStream().write(octets, 10000, 9999);
				response.getOutputStream().write(committed ? 'c' : 'u');
				break;
			}
			case "flush": {
				PrintWriter out = response.getWriter();
				out.print("before=" + response.isCommitted());
				response.flushBuffer();
				out.print(" after=" + response.isCommitted());
				response.setHeader("X-Late", "ignored");
				response.setStatus(500);
				try {
					response.reset();
				}
				catch (IllegalStateException ex) {
					out.print(" reset=IllegalStateException");
				}
				try {
					response.resetBuffer();
				}
				catch (IllegalStateException ex) {
					out.print(" resetBuffer=IllegalStateException");
				}
				try {
					response.setBufferSize(100);
				}
				catch (IllegalStateException ex) {
					out.print(" setBufferSize=IllegalStateException");
				}
				break;
			}
			case "length":
				response.setContentLength(3);
				response.getOutputStream().print("abcdef");
				break;
			case "both":
				response.getOutputStream();
				try {
					response.getWriter();
				}
				catch (IllegalStateException ex) {
					response.getOutputStream().print("getWriter=IllegalStateException");
				}
				break;
			case "both-writer":
				response.getWriter();
				try {
					response.getOutputStream();
				}
				catch (IllegalStateException ex) {
					response.getWriter().print("getOutputStream=IllegalStateException");
				}
				break;
			case "reset":
				response.setHeader("X-Gone", "1");
				response.setStatus(202);
				response.setContentType("text/html");
				response.getWriter().print("junk");
				response.reset();
				response.getOutputStream().print("clean " + response.getStatus() + " " + response.getContentType());
				break;
			case "resetBuffer":
				response.setHeader("X-Kept", "1");
				response.setStatus(202);
				response.getWriter().print("junk");
				response.resetBuffer();
				response.getWriter().print("clean");
				break;
			case "buffer": {
				int usual = response.getBufferSize();
				response.setBufferSize(16);
				int asked = response.getBufferSize();
				response.setBufferSize(usual);
				PrintWriter out = response.getWriter();
				out.print("usual=" + (usual >= 8192) + " asked=" + (asked >= 16));
				try {
					response.setBufferSize(32);
				}
				catch (IllegalStateException ex) {
					out.print(" written=IllegalStateException");
				}
				break;
			}
			case "fields": {
				response.setHeader("X-Set", "1");
				response.setHeader("X-Set", "2");
				response.addHeader("X-Add", "1");
				response.addHeader("x-add", "2");
				response.setIntHeader("X-Int", 7);
				response.addIntHeader("X-Int", 8);
				response.setDateHeader("Last-Modified", 784111777000L);
				response.setHeader("X-Removed", "1");
				response.setHeader("X-Removed", null);
				response.addHeader("X-Null", null);
				response.setHeader("Content-Type", "text/plain");
				response.getWriter()
					.print(response.containsHeader("x-set") + " " + response.containsHeader("X-None") + " "
							+ response.getHeader("X-ADD") + " " + response.getHeaders("X-Add") + " "
							+ response.getHeaders("X-None") + " " + response.getHeader("X-None") + " "
							+ response.containsHeader("X-Removed") + " " + response.containsHeader("X-Null") + " "
							+ response.getContentType() + " " + new TreeSet<>(lower(response.getHeaderNames())));
				break;
			}
			case "type": {
				StringBuilder told = new StringBuilder();
				told.append(response.getContentType()).append(' ').append(response.getCharacterEncoding());
				response.setContentType("text/html");
				told.append(' ').append(response.getContentType()).append(' ').append(response.getCharacterEncoding());
				response.setContentType("text/html; charset=UTF-8");
				told.append(' ').append(response.getContentType()).append(' ').append(response.getCharacterEncoding());
				response.setCharacterEncoding("Shift_JIS");
				told.append(' ').append(response.getContentType());
				response.setContentType("text/plain");
				told.append(' ').append(response.getContentType());
				response.setCharacterEncoding("utf-8");
				PrintWriter out = response.getWriter();
				response.setCharacterEncoding("ISO-8859-1");
				response.setContentType("text/csv; charset=US-ASCII");
				told.append(' ').append(response.getContentType()).append(' ').append(response.getCharacterEncoding());
				out.print(told + " あ");
				break;
			}
			case "writer-default":
				response.setContentType("text/plain");
				response.getWriter().print(response.getContentType() + " éあ");
				break;
			case "no-type":
				response.getWriter().print(response.getContentType() + " " + response.getCharacterEncoding());
				break;
			case "stream-type":
				response.setContentType("text/plain");
				response.getOutputStream().print(response.getContentType() + " " + response.getCharacterEncoding());
				break;
			case "status":
				response.setStatus(201);
				response.getWriter().print("made " + response.getStatus());
				break;
			case "no-content":
				response.setStatus(204);
				break;
			case "not-modified":
				response.setStatus(304);
				response.setHeader("ETag", "\"v1\"");
				break;
			case "cookie": {
				Cookie plain = new Cookie("plain", "value");
				response.addCookie(plain);
				Cookie full = new Cookie("full", "v");
				full.setPath("/app");
				full.setDomain("Example.COM");
				full.setMaxAge(60);
				full.setSecure(true);
				full.setHttpOnly(true);
				full.setAttribute("SameSite", "Lax");
				response.addCookie(full);
				break;
			}
			case "locale":
				response.setLocale(Locale.JAPAN);
				response.getWriter().print(response.getLocale().toLanguageTag());
				break;
			case "close":
				response.getWriter().print("kept");
				response.getWriter().close();
				response.getWriter().print(" dropped");
				break;
			case "utf8": {
				response.setCharacterEncoding("UTF-8");
				PrintWriter out = response.getWriter();
				out.print("\ud83d");
				out.print("\ude00あ");
				break;
			}
			default:
				throw new IllegalArgumentException(kind);
		}
	}

	private static List<String> lower(Iterable<String> names) {
		List<String> lower = new ArrayList<>();
		for (String name : names) {
			lower.add(name.toLowerCase(Locale.ROOT));
		}
		return lower;
	}

	private static void errors(HttpServletRequest request, HttpServletResponse response) throws Exception {
		String kind = request.getParameter("case");
		response.setHeader("X-Before", "1");
		switch (kind) {
			case "error":
				response.getWriter().print("junk");
				response.sendError(404);
				response.getWriter().print("dropped");
				response.setHeader("X-After", "1");
				break;
			case "message":
				response.sendError(418, "<teapot> & \"cup\"");
				break;
			case "committed": {
				response.getWriter().print("sent");
				response.flushBuffer();
				try {
					response.sendError(500);
				}
				catch (IllegalStateException ex) {
					response.getWriter().print(" sendError=IllegalStateException");
				}
				try {
					response.sendRedirect("/elsewhere");
				}
				catch (IllegalStateException ex) {
					response.getWriter().print(" sendRedirect=IllegalStateException");
				}
				break;
			}
			case "redirect":
				response.getWriter().print("junk");
				response.sendRedirect(request.getParameter("to"));
				response.getWriter().print("dropped");
				break;
			case "redirect-status":
				response.sendRedirect("/see/other", 303);
				break;
			case "redirect-kept":
				response.getWriter().print("kept");
				response.sendRedirect("/kept", 307, false);
				break;
			case "servlet-exception":
				response.getWriter().print("junk");
				throw new ServletException("failed");
			case "runtime-exception":
				throw new IllegalArgumentException("failed");
			case "io-exception":
				throw new IOException("failed");
			case "after-commit":
				response.getWriter().print("sent");
				response.flushBuffer();
				throw new ServletException("late");
			default:
				throw new IllegalArgumentException(kind);
		}
	}

	private static void dispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
		String kind = request.getParameter("case");
		PrintWriter out = response.getWriter();
		switch (kind) {
			case "forward":
				response.setHeader("X-Kept", "1");
				out.print("dropped");
				request.setAttribute("handed", "over");
				request.getRequestDispatcher(request.getParameter("to")).forward(request, response);
				out.print("dropped too");
				break;
			case "forward-committed":
				out.print("sent ");
				response.flushBuffer();
				try {
					request.getRequestDispatcher("/target/x").forward(request, response);
				}
				catch (IllegalStateException ex) {
					out.print("forward=IllegalStateException");
				}
				break;
			case "include":
				out.print("[before]");
				request.getRequestDispatcher(request.getParameter("to")).include(request, response);
				out.print("[after " + request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) + " "
						+ request.getDispatcherType() + " " + request.getParameter("added") + "]");
				break;
			case "named":
				request.getServletContext().getNamedDispatcher("target").forward(request, response);
				break;
			case "named-include":
				out.print("[before]");
				request.getServletContext().getNamedDispatcher("target").include(request, response);
				out.print("[after]");
				break;
			case "none":
				out.print(request.getServletContext().getNamedDispatcher("nobody") + " "
						+ request.getServletContext().getRequestDispatcher("relative"));
				break;
			default:
				throw new IllegalArgumentException(kind);
		}
	}

	private static void target(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setHeader("X-Target", "1");
		response.setStatus(203);
		response.getWriter()
			.print("target " + request.getDispatcherType() + " uri=" + request.getRequestURI() + " servlet="
					+ request.getServletPath() + " info=" + request.getPathInfo() + " query=" + request.getQueryString()
					+ " mapping=" + mapping(request.getHttpServletMapping()) + " added="
					+ Arrays.toString(request.getParameterValues("added")) + " case=" + request.getParameter("case")
					+ " wrapped=" + request.getParameter("wrapped") + " handed=" + request.getAttribute("handed")
					+ " forward=" + request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) + ","
					+ request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH) + ","
					+ request.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH) + ","
					+ request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO) + ","
					+ request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING) + ","
					+ mapping((HttpServletMapping) request.getAttribute(RequestDispatcher.FORWARD_MAPPING))
					+ " include=" + request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) + ","
					+ request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH) + ","
					+ request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH) + ","
					+ request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO) + ","
					+ request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING) + ","
					+ mapping((HttpServletMapping) request.getAttribute(RequestDispatcher.INCLUDE_MAPPING)));
	}

	/** Answers once a second request is being served at the same time. */
	private void together(HttpServletRequest request, HttpServletResponse response) throws Exception {
		this.together.await(20, TimeUnit.SECONDS);
		response.getWriter().print("together");
	}

}
