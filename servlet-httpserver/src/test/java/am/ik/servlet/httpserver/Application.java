package am.ik.servlet.httpserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ReadListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import jakarta.servlet.http.Part;

/**
 * The application every container is given: servlets that answer what the container told
 * them, one family of behaviour a servlet.
 */
final class Application {

	/** What the container did to the application, in order. */
	final List<String> events = new CopyOnWriteArrayList<>();

	private final CyclicBarrier together = new CyclicBarrier(2);

	/** What the listeners of the sessions and the values bound to one heard, in order. */
	final List<String> sessionEvents = new CopyOnWriteArrayList<>();

	/** The directory the parts of a multipart body larger than its threshold go to. */
	final Path uploads;

	/** The asynchronous requests, by the id each was asked with. */
	private final Map<String, Trace> traces = new ConcurrentHashMap<>();

	Application() {
		try {
			this.uploads = Files.createTempDirectory("servlet-httpserver-uploads");
			// empty once the requests are complete
			this.uploads.toFile().deleteOnExit();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

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
		// Sessions tracked by URLs alone.
		context.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.URL));
		servlet(context, "session", this::session, "/session");
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
				// the query, not the parameters: a form read here could not take a
				// charset set later
				String query = request.getQueryString();
				if (query != null && query.contains("&listened")) {
					trace(request).events.add("initialized");
				}
			}

			@Override
			public void requestDestroyed(ServletRequestEvent event) {
				HttpServletRequest request = (HttpServletRequest) event.getServletRequest();
				if (request.getRequestURI().endsWith("/listened")) {
					Application.this.events.add("request destroyed " + request.getAttribute("heard"));
				}
				if (request.getRequestURI().startsWith("/app/async/")) {
					Trace trace = trace(request);
					if (request.getParameter("listened") != null) {
						trace.events.add("destroyed");
					}
					trace.destroyed.countDown();
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
		servlet(context, "failed", Application::failed, "/failed/*");
		// Marks the error dispatches of the pages.
		context.addFilter("erroring", (request, response, chain) -> {
			response.getWriter().print("<" + request.getDispatcherType() + ">");
			chain.doFilter(request, response);
		}).addMappingForUrlPatterns(EnumSet.of(DispatcherType.ERROR), true, "/failed/*");
		servlet(context, "dispatch", Application::dispatch, "/dispatch", "/dispatch/deeper/*");
		servlet(context, "target", Application::target, "/target/*", "/filtered/*");
		servlet(context, "together", this::together, "/together");
		servlet(context, "session", this::session, "/session");
		context.addListener(new Heard());
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
		servlet(context, "async", this::async, "/async/*").setAsyncSupported(true);
		servlet(context, "sync", Application::sync, "/sync");
		// Parts of 64 octets at most, a body of 1024, in a file above 16.
		servlet(context, "parts", this::parts, "/parts")
			.setMultipartConfig(new MultipartConfigElement(this.uploads.toString(), 64, 1024, 16));
		servlet(context, "unconfigured", this::parts, "/unconfigured");
		// Marks the dispatches it is in; supports asynchronous requests.
		FilterRegistration.Dynamic asynchronous = context.addFilter("asynchronous", (request, response, chain) -> {
			Object before = request.getAttribute("filtered");
			request.setAttribute("filtered", (before == null ? "" : before + ",") + request.getDispatcherType());
			chain.doFilter(request, response);
		});
		asynchronous.setAsyncSupported(true);
		asynchronous.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC), true,
				"/async/*");
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

	/**
	 * The application of a document root, served by the container's default servlet: a
	 * filter on every path, what the context answers of its resources, the dispatches to
	 * them.
	 */
	void registerResources(ServletContext context) {
		context.addFilter("marking", (request, response, chain) -> {
			((HttpServletResponse) response).setHeader("X-Filtered", "yes");
			chain.doFilter(request, response);
		}).addMappingForUrlPatterns(null, false, "/*");
		servlet(context, "members", Application::members, "/members");
		servlet(context, "including", Application::including, "/including/*");
	}

	/** What a call answers, or the simple name of the class of what it throws. */
	private static String answered(Callable<Object> call) {
		try {
			Object answer = call.call();
			if (answer instanceof InputStream in) {
				try (in) {
					return "[" + new String(in.readAllBytes(), StandardCharsets.UTF_8) + "]";
				}
			}
			return answer instanceof Set<?> set ? new TreeSet<>(set).toString() : String.valueOf(answer);
		}
		catch (Exception ex) {
			return ex.getClass().getSimpleName();
		}
	}

	private static void members(HttpServletRequest request, HttpServletResponse response) throws IOException {
		ServletContext context = request.getServletContext();
		PrintWriter out = response.getWriter();
		for (String path : new String[] { "/style.css", "style.css", "/missing", "/sub", "/sub/../style.css", "/../x",
				"/style.css/" }) {
			out.println("getResource " + path + " " + answered(() -> context.getResource(path)));
		}
		for (String path : new String[] { "/notes.txt", "notes.txt", "/sub" }) {
			out.println("getResourceAsStream " + path + " " + answered(() -> context.getResourceAsStream(path)));
		}
		for (String path : new String[] { "/", "/sub", "/missing/", "/style.css" }) {
			out.println("getResourcePaths " + path + " " + answered(() -> context.getResourcePaths(path)));
		}
		for (String path : new String[] { "/style.css", "style.css", "/missing", "/", "/sub", "/../x" }) {
			out.println("getRealPath " + path + " " + answered(() -> context.getRealPath(path)));
		}
	}

	/**
	 * Includes or forwards to a resource, by its path or to the default servlet by name.
	 */
	private static void including(HttpServletRequest request, HttpServletResponse response) throws Exception {
		String to = request.getParameter("to");
		RequestDispatcher dispatcher = to == null ? request.getServletContext().getNamedDispatcher("default")
				: request.getRequestDispatcher(to);
		if (request.getParameter("forward") != null) {
			dispatcher.forward(request, response);
			return;
		}
		PrintWriter out = response.getWriter();
		out.print("[");
		try {
			dispatcher.include(request, response);
		}
		catch (IOException ex) {
			out.print(ex.getClass().getSimpleName());
		}
		out.print("]");
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
			case "paged":
				// an error page answers the status (Container.ERROR_STATUS)
				response.getWriter().print("junk");
				response.sendError(410, "gone away");
				request.setAttribute("committed", response.isCommitted());
				response.getWriter().print("dropped");
				break;
			case "paged-bare":
				response.sendError(410);
				break;
			case "refused":
				throw new Refused("refused");
			case "refused-below":
				throw new Refused("refused below") {
				};
			case "refused-wrapped":
				throw new ServletException("wrapped", new Refused("refused inside"));
			case "paged-forward":
				request.getRequestDispatcher("/errors?case=paged").forward(request, response);
				break;
			default:
				throw new IllegalArgumentException(kind);
		}
	}

	/** What an error page of an exception type answers (Container.ERROR_BY_TYPE). */
	static class Refused extends RuntimeException {

		Refused(String message) {
			super(message);
		}

	}

	/** An error page: what the container told it of the error. */
	private static void failed(HttpServletRequest request, HttpServletResponse response) throws Exception {
		response.setHeader("X-Page", "1");
		response.setStatus(299);
		response.setContentType("text/plain");
		PrintWriter out = response.getWriter();
		out.print(request.getDispatcherType() + " " + request.getMethod() + " " + request.getRequestURI() + " "
				+ request.getServletPath() + request.getPathInfo() + " committed=" + request.getAttribute("committed"));
		for (String name : new String[] { RequestDispatcher.ERROR_STATUS_CODE, RequestDispatcher.ERROR_MESSAGE,
				RequestDispatcher.ERROR_EXCEPTION_TYPE, RequestDispatcher.ERROR_EXCEPTION,
				RequestDispatcher.ERROR_REQUEST_URI, RequestDispatcher.ERROR_SERVLET_NAME,
				RequestDispatcher.ERROR_QUERY_STRING, RequestDispatcher.ERROR_METHOD }) {
			Object value = request.getAttribute(name);
			out.print(" " + name.substring("jakarta.servlet.error.".length()) + "="
					+ (value instanceof Throwable
							? value.getClass().getSimpleName() + ":" + ((Throwable) value).getMessage()
							: value instanceof Class ? ((Class<?>) value).getSimpleName() : value));
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

	/**
	 * What an asynchronous request did, and its ends: its listener heard it complete, the
	 * request listener heard it destroyed (one before the other, by the container).
	 */
	static final class Trace {

		final List<String> events = new CopyOnWriteArrayList<>();

		final CountDownLatch completed = new CountDownLatch(1);

		final CountDownLatch destroyed = new CountDownLatch(1);

		/** Counted down once the request is asynchronous and its context kept. */
		final CountDownLatch held = new CountDownLatch(1);

		volatile AsyncContext context;

		/** What was done, once the request ended. */
		List<String> ended() throws InterruptedException {
			if (!this.completed.await(20, TimeUnit.SECONDS) || !this.destroyed.await(20, TimeUnit.SECONDS)) {
				throw new IllegalStateException("not ended: " + this.events);
			}
			return this.events;
		}

	}

	Trace trace(String id) {
		return this.traces.computeIfAbsent(id, made -> new Trace());
	}

	private Trace trace(ServletRequest request) {
		return trace(request.getParameter("id"));
	}

	/**
	 * Hears an asynchronous cycle; one added first tells whether it was supplied objects.
	 */
	private static final class Cycle implements AsyncListener {

		private final Trace trace;

		private final boolean supplied;

		Cycle(Trace trace, boolean supplied) {
			this.trace = trace;
			this.supplied = supplied;
		}

		@Override
		public void onComplete(AsyncEvent event) {
			this.trace.events
				.add("complete" + (this.supplied ? " supplied=" + (event.getSuppliedRequest() != null) : ""));
			this.trace.completed.countDown();
		}

		@Override
		public void onTimeout(AsyncEvent event) throws IOException {
			AsyncContext async = event.getAsyncContext();
			this.trace.events.add("timeout");
			if (async.getRequest().getParameter("late") != null) {
				// the dispatch that started the cycle has returned
				this.trace.events.add("late " + thrown(() -> async.setTimeout(1000)) + ","
						+ thrown(() -> async.addListener(new Cycle(new Trace(), false))));
			}
			if (async.getRequest().getParameter("answer") != null) {
				async.getResponse().getWriter().print("answered at the timeout");
				async.complete();
			}
		}

		@Override
		public void onError(AsyncEvent event) {
			this.trace.events.add("error " + event.getThrowable().getMessage());
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
			this.trace.events.add("start");
		}

	}

	/** What an action throws: its class's simple name, or ok. */
	static String thrown(Runnable action) {
		try {
			action.run();
			return "ok";
		}
		catch (RuntimeException ex) {
			return ex.getClass().getSimpleName();
		}
	}

	private static void print(AsyncContext async, String text) {
		try {
			async.getResponse().getWriter().print(text);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The paths an asynchronous dispatch reaches, and where the request went first. */
	private static String dispatched(HttpServletRequest request) {
		return request.getDispatcherType() + " uri=" + request.getRequestURI() + " servlet=" + request.getServletPath()
				+ " info=" + request.getPathInfo() + " query=" + request.getQueryString() + " async="
				+ request.getAttribute(AsyncContext.ASYNC_REQUEST_URI) + ","
				+ request.getAttribute(AsyncContext.ASYNC_CONTEXT_PATH) + ","
				+ request.getAttribute(AsyncContext.ASYNC_SERVLET_PATH) + ","
				+ request.getAttribute(AsyncContext.ASYNC_PATH_INFO) + ","
				+ request.getAttribute(AsyncContext.ASYNC_QUERY_STRING) + ","
				+ mapping((HttpServletMapping) request.getAttribute(AsyncContext.ASYNC_MAPPING)) + " filtered="
				+ request.getAttribute("filtered") + " started=" + request.isAsyncStarted();
	}

	/**
	 * Asynchronous requests: one family a case, each its trace by the id it was asked
	 * with.
	 */
	private void async(HttpServletRequest request, HttpServletResponse response) throws Exception {
		String kind = request.getParameter("case");
		Trace trace = trace(request);
		if (request.getDispatcherType() == DispatcherType.ASYNC) {
			if (kind.equals("thrown-dispatch")) {
				throw new ServletException("thrown by the dispatch");
			}
			if (kind.equals("restart")) {
				// a cycle of its own: the listeners of the one before hear it start
				AsyncContext again = request.startAsync();
				again.addListener(new Cycle(trace, false));
				again.start(() -> {
					print(again, "restarted");
					again.complete();
				});
				return;
			}
			response.getWriter().print(dispatched(request) + " x=" + request.getParameter("x"));
			return;
		}
		switch (kind) {
			case "start": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, true));
				trace.events.add("started=" + request.isAsyncStarted() + " original="
						+ async.hasOriginalRequestAndResponse() + " timeout=" + async.getTimeout() + " same="
						+ (request.getAsyncContext() == async) + " again=" + thrown(request::startAsync));
				async.start(() -> {
					print(async, "by a task, committed=" + async.getResponse().isCommitted() + " started="
							+ request.isAsyncStarted());
					async.complete();
				});
				break;
			}
			case "dispatch": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				async.start(async::dispatch);
				break;
			}
			case "dispatch-inside": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				async.dispatch("/async/there?case=print&x=1");
				trace.events.add("started=" + request.isAsyncStarted() + " again="
						+ thrown(() -> async.dispatch("/async/there")) + " complete=" + thrown(async::complete));
				response.getWriter().print("kept|");
				break;
			}
			case "held": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				trace.context = async;
				trace.held.countDown();
				break;
			}
			case "timeout": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				async.setTimeout(200);
				trace.context = async;
				response.setHeader("X-Before", "1");
				break;
			}
			case "thrown": {
				request.startAsync().addListener(new Cycle(trace, false));
				response.setHeader("X-Before", "1");
				response.getWriter().print("junk");
				throw new ServletException("thrown after startAsync");
			}
			case "thrown-task": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				async.setTimeout(500);
				async.start(() -> {
					throw new IllegalStateException("thrown by a task");
				});
				break;
			}
			case "restart":
			case "thrown-dispatch": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				async.start(async::dispatch);
				break;
			}
			case "refusals": {
				StringBuilder told = new StringBuilder();
				ServletInputStream in = request.getInputStream();
				told.append("readListener=").append(thrown(() -> in.setReadListener(new Echo(null, trace))));
				told.append(" getAsyncContext=").append(thrown(request::getAsyncContext));
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				told.append(" twice=").append(thrown(request::startAsync));
				response.getWriter().print(told);
				async.complete();
				break;
			}
			case "forwarded":
				request.getRequestDispatcher("/async/b?case=forwarded-target&how=" + request.getParameter("how"))
					.forward(request, response);
				break;
			case "forwarded-target": {
				// startAsync() dispatches to where the container dispatched last, the
				// pair to where the request it is given is
				AsyncContext async = "pair".equals(request.getParameter("how"))
						? request.startAsync(request, new HttpServletResponseWrapper(response)) : request.startAsync();
				async.addListener(new Cycle(trace, false));
				trace.events.add("original=" + async.hasOriginalRequestAndResponse());
				async.start(async::dispatch);
				break;
			}
			case "read": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				ServletInputStream in = request.getInputStream();
				trace.events.add("ready=" + in.isReady() + " finished=" + in.isFinished());
				in.setReadListener(new Echo(async, trace));
				trace.events.add("again=" + thrown(() -> in.setReadListener(new Echo(async, trace))) + " null="
						+ thrown(() -> in.setReadListener(null)));
				if (request.getContentLength() <= 0) {
					trace.events.add("blocking=" + thrown(() -> {
						try {
							in.readAllBytes();
						}
						catch (IOException ex) {
							throw new UncheckedIOException(ex);
						}
					}));
				}
				else {
					async.setTimeout(500);
				}
				break;
			}
			case "write": {
				AsyncContext async = request.startAsync();
				async.addListener(new Cycle(trace, false));
				ServletOutputStream out = response.getOutputStream();
				WriteListener writing = new WriteListener() {
					@Override
					public void onWritePossible() throws IOException {
						trace.events.add("writable ready=" + out.isReady());
						out.print("written");
						async.complete();
					}

					@Override
					public void onError(Throwable t) {
						trace.events.add("write error " + t.getMessage());
					}
				};
				out.setWriteListener(writing);
				trace.events.add("again=" + thrown(() -> out.setWriteListener(writing)));
				break;
			}
			default:
				throw new IllegalArgumentException(kind);
		}
	}

	/**
	 * Reads what is there while the stream is ready, and answers the body once it is
	 * read; the request asked to throws instead.
	 */
	private static final class Echo implements ReadListener {

		private final AsyncContext async;

		private final Trace trace;

		private final StringBuilder read = new StringBuilder();

		Echo(AsyncContext async, Trace trace) {
			this.async = async;
			this.trace = trace;
		}

		@Override
		public void onDataAvailable() throws IOException {
			ServletInputStream in = this.async.getRequest().getInputStream();
			if (this.async.getRequest().getParameter("throw") != null) {
				throw new IOException("thrown by onDataAvailable");
			}
			byte[] chunk = new byte[4];
			while (in.isReady() && !in.isFinished()) {
				int count = in.read(chunk);
				this.trace.events.add("read " + count + " finished=" + in.isFinished());
				if (count < 0) {
					break;
				}
				this.read.append(new String(chunk, 0, count, StandardCharsets.ISO_8859_1));
			}
		}

		@Override
		public void onAllDataRead() {
			this.trace.events.add("all read");
			print(this.async, "echo " + this.read);
			this.async.complete();
		}

		@Override
		public void onError(Throwable t) {
			this.trace.events.add("read error " + t.getMessage());
		}

	}

	/**
	 * The parts of a multipart body and the parameters they make, in the order the
	 * {@code X-Mode} field asks: the parts first, the parameters first, the body's stream
	 * first, or each part with a file name written into the directory and read again.
	 */
	private void parts(HttpServletRequest request, HttpServletResponse response) throws Exception {
		response.setCharacterEncoding("UTF-8");
		PrintWriter out = response.getWriter();
		String mode = String.valueOf(request.getHeader("X-Mode"));
		if (mode.equals("stream")) {
			out.println("read=" + request.getInputStream().read());
		}
		if (mode.equals("parameters")) {
			out.println(parameterMap(request));
		}
		Collection<Part> parts;
		try {
			parts = request.getParts();
		}
		catch (Exception ex) {
			out.print("getParts=" + failure(ex));
			try {
				out.print(" getPart=" + request.getPart("a"));
			}
			catch (Exception again) {
				out.print(" getPart=" + failure(again));
			}
			return;
		}
		try (Stream<Path> files = Files.list(this.uploads)) {
			out.println("files=" + files.count());
		}
		for (Part part : parts) {
			Set<String> names = new TreeSet<>();
			for (String name : part.getHeaderNames()) {
				names.add(name.toLowerCase(Locale.ROOT));
			}
			out.println(part.getName() + " file=" + part.getSubmittedFileName() + " type="
					+ part.getHeader("CONTENT-TYPE") + " size=" + part.getSize() + " fields=" + names + " ["
					+ new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8) + "]");
			if (mode.equals("write") && part.getSubmittedFileName() != null) {
				part.write("saved-" + part.getName());
				Path saved = this.uploads.resolve("saved-" + part.getName());
				out.print("written " + Files.readString(saved) + ", then ");
				try {
					out.println(new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
				}
				catch (IOException ex) {
					out.println("IOException");
				}
				Files.delete(saved);
				part.delete();
			}
		}
		Part a = request.getPart("a");
		out.print("getPart=" + (a == null ? null : a.getName()));
		if (!mode.equals("parameters")) {
			out.print("\n" + parameterMap(request));
		}
	}

	private static String parameterMap(HttpServletRequest request) {
		try {
			StringBuilder map = new StringBuilder("parameters=");
			for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
				map.append(parameter.getKey()).append(Arrays.toString(parameter.getValue()));
			}
			return map.toString();
		}
		catch (RuntimeException ex) {
			return "parameters=" + failure(ex);
		}
	}

	/** What kind of exception was thrown, and for a ServletException, of its cause. */
	private static String failure(Throwable ex) {
		String kind = ex instanceof IllegalStateException ? "IllegalStateException"
				: ex instanceof IOException ? "IOException" : ex.getClass().getSimpleName();
		return ex instanceof ServletException && ex.getCause() != null ? kind + "(" + failure(ex.getCause()) + ")"
				: kind;
	}

	/** A servlet that does not support asynchronous requests. */
	private static void sync(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.getWriter()
			.print("supported=" + request.isAsyncSupported() + " startAsync=" + thrown(request::startAsync)
					+ " started=" + request.isAsyncStarted());
	}

	/** The listeners of the sessions, one object. */
	private final class Heard implements HttpSessionListener, HttpSessionAttributeListener, HttpSessionIdListener {

		@Override
		public void sessionCreated(HttpSessionEvent event) {
			Application.this.sessionEvents.add("created " + event.getSession().isNew());
		}

		@Override
		public void sessionDestroyed(HttpSessionEvent event) {
			Application.this.sessionEvents.add("destroyed a=" + event.getSession().getAttribute("a"));
		}

		@Override
		public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
			Application.this.sessionEvents.add("id changed " + !oldSessionId.equals(event.getSession().getId()));
		}

		@Override
		public void attributeAdded(HttpSessionBindingEvent event) {
			Application.this.sessionEvents.add("added " + event.getName() + "=" + event.getValue());
		}

		@Override
		public void attributeRemoved(HttpSessionBindingEvent event) {
			Application.this.sessionEvents.add("removed " + event.getName() + "=" + event.getValue());
		}

		@Override
		public void attributeReplaced(HttpSessionBindingEvent event) {
			Application.this.sessionEvents.add("replaced " + event.getName() + "=" + event.getValue() + " by "
					+ event.getSession().getAttribute(event.getName()));
		}

	}

	/** A value that hears it is bound to a session and unbound. */
	private final class Bound implements HttpSessionBindingListener {

		private final String name;

		Bound(String name) {
			this.name = name;
		}

		@Override
		public void valueBound(HttpSessionBindingEvent event) {
			Application.this.sessionEvents.add("bound " + this.name);
		}

		@Override
		public void valueUnbound(HttpSessionBindingEvent event) {
			Application.this.sessionEvents.add("unbound " + this.name);
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	private static String requested(HttpServletRequest request, HttpSession session) {
		String requested = request.getRequestedSessionId();
		return "requested="
				+ (requested == null ? null : session != null && requested.equals(session.getId()) ? "this" : requested)
				+ " valid=" + request.isRequestedSessionIdValid() + " cookie="
				+ request.isRequestedSessionIdFromCookie() + " url=" + request.isRequestedSessionIdFromURL();
	}

	private void session(HttpServletRequest request, HttpServletResponse response) throws Exception {
		String kind = request.getParameter("case");
		PrintWriter out = response.getWriter();
		switch (kind) {
			case "create": {
				HttpSession session = request.getSession();
				session.setAttribute("a", new Bound("one"));
				session.setAttribute("a", new Bound("two"));
				SessionCookieConfig cookie = request.getServletContext().getSessionCookieConfig();
				out.print("new=" + session.isNew() + " max=" + session.getMaxInactiveInterval() + " "
						+ requested(request, session) + " encoded="
						+ response.encodeURL("x?y#z").replace(session.getId(), "ID") + " config=" + cookie.getName()
						+ "," + cookie.getPath() + "," + cookie.isHttpOnly() + "," + cookie.isSecure() + ","
						+ cookie.getMaxAge() + "," + cookie.getAttributes() + " modes="
						+ new TreeSet<>(request.getServletContext().getDefaultSessionTrackingModes()) + ","
						+ new TreeSet<>(request.getServletContext().getEffectiveSessionTrackingModes()) + " id="
						+ (session.getId().matches("[0-9A-F]{32}") ? "hex" : "own"));
				if (request.getParameter("show") != null) {
					out.print(" " + session.getId());
				}
				break;
			}
			case "read": {
				HttpSession session = request.getSession(false);
				out.print("found=" + (session != null) + " " + requested(request, session));
				if (session != null) {
					out.print(" new=" + session.isNew() + " a=" + session.getAttribute("a") + " encoded="
							+ response.encodeURL("x?y#z").replace(session.getId(), "ID") + " same="
							+ (request.getSession(false) == session) + " times="
							+ (session.getCreationTime() <= session.getLastAccessedTime()));
				}
				break;
			}
			case "change": {
				HttpSession session = request.getSession(false);
				String before = session.getId();
				String after = request.changeSessionId();
				out.print("changed=" + !before.equals(after) + " now=" + after.equals(session.getId()) + " "
						+ requested(request, request.getSession(false)).replace(before, "before") + " a="
						+ session.getAttribute("a"));
				break;
			}
			case "accessor": {
				HttpSession session = request.getSession(false);
				HttpSession.Accessor accessor = session.getAccessor();
				accessor.access(accessed -> accessed.setAttribute("b", "by accessor"));
				out.print("b=" + session.getAttribute("b"));
				session.invalidate();
				try {
					accessor.access(accessed -> {
					});
				}
				catch (IllegalStateException ex) {
					out.print(" invalidated=IllegalStateException");
				}
				break;
			}
			case "invalidate": {
				HttpSession session = request.getSession(false);
				String before = session.getId();
				session.invalidate();
				out.print("after=" + request.getSession(false) + " "
						+ requested(request, null).replace(before, "before"));
				try {
					session.getAttribute("a");
				}
				catch (IllegalStateException ex) {
					out.print(" getAttribute=IllegalStateException");
				}
				try {
					session.invalidate();
				}
				catch (IllegalStateException ex) {
					out.print(" invalidate=IllegalStateException");
				}
				HttpSession made = request.getSession(true);
				out.print(" made=" + made.isNew() + "," + !made.getId().equals(before));
				break;
			}
			case "committed":
				out.print("sent");
				response.flushBuffer();
				try {
					request.getSession(true);
				}
				catch (IllegalStateException ex) {
					out.print(" getSession=IllegalStateException");
				}
				break;
			case "events":
				out.print(this.sessionEvents);
				break;
			default:
				throw new IllegalArgumentException(kind);
		}
	}

}
