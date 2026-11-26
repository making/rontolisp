package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import com.sun.net.httpserver.HttpExchange;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import jakarta.servlet.http.MappingMatch;
import org.jspecify.annotations.Nullable;

/**
 * The one web application of a server: what is registered, how a path is mapped to a
 * servlet and its filters, and the course of a request from the exchange to the answer.
 *
 * <p>
 * Registration is open until the context is initialized; from then on the tables are only
 * read, by every request thread.
 */
final class WebContext implements ServletContext {

	private static final String SERVER_INFO = "servlet-httpserver/0.1";

	private final String contextPath;

	private final ClassLoader loader;

	private final Map<String, Object> attributes = new ConcurrentHashMap<>();

	private final Map<String, String> initParameters = new LinkedHashMap<>();

	private final Map<String, ServletEntry> servlets = new LinkedHashMap<>();

	private final Map<String, ServletEntry> patterns = new LinkedHashMap<>();

	private final Map<String, FilterEntry> filters = new LinkedHashMap<>();

	private final List<FilterMapping> filterMappings = new ArrayList<>();

	private int filterMappingsBefore;

	private final List<ServletContextListener> contextListeners = new ArrayList<>();

	private final List<ServletContextAttributeListener> attributeListeners = new ArrayList<>();

	private final List<ServletRequestListener> requestListeners = new ArrayList<>();

	final List<ServletRequestAttributeListener> requestAttributeListeners = new ArrayList<>();

	final List<HttpSessionListener> sessionListeners = new ArrayList<>();

	final List<HttpSessionAttributeListener> sessionAttributeListeners = new ArrayList<>();

	final List<HttpSessionIdListener> sessionIdListeners = new ArrayList<>();

	private final List<FilterEntry> initializedFilters = new ArrayList<>();

	private int notified;

	private volatile boolean initialized;

	private @Nullable String requestCharacterEncoding;

	private @Nullable String responseCharacterEncoding;

	private int sessionTimeout = 30;

	// Two flags, not a set: an EnumSet in every context is some 45 KB of a
	// compiled program that never asks for a session.
	private boolean trackCookies = true;

	private boolean trackUrls;

	private @Nullable CookieConfig cookieConfig;

	private @Nullable SessionStore sessionStore;

	private @Nullable Sessions sessions;

	/** What runs the tasks of {@code AsyncContext.start}: the server's executor. */
	private @Nullable Executor executor;

	/** The asynchronous requests in hand, in no thread's hand. */
	private final List<Async> waiting = new ArrayList<>();

	/** The deadlines of the asynchronous cycles, made by the first. */
	private @Nullable Timeouts timeouts;

	private boolean stopping;

	/** The directory whose files are the application's first, or null. */
	private @Nullable DocumentRoot documentRoot;

	/** The class path's resources, where it holds {@code META-INF/resources}, or null. */
	private @Nullable ClassPathResources classPath;

	/** The error pages, where the builder gave one. */
	private @Nullable ErrorPages errorPages;

	/** The longest form read into parameters, negative for no limit. */
	private int maxFormSize = 2 * 1024 * 1024;

	/** Where the log goes, or null for the standard error. */
	private @Nullable BiConsumer<String, @Nullable Throwable> log;

	/** Whether the server is an HTTPS server. */
	private boolean secure;

	/**
	 * A context, open for registration until it starts.
	 * @param loader the class loader of the application
	 */
	WebContext(String contextPath, ClassLoader loader) {
		this.contextPath = contextPath;
		this.loader = loader;
		// Whether the class path holds the directory META-INF/resources: a question a
		// program compiled ahead of time has answered where it was compiled, so that one
		// with none holds nothing of what reads it. Asked there of a stream, which makes
		// no URL; under a JDK of the URL, which opens nothing.
		if (System.getProperty("java.class.path") != null) {
			if (loader.getResource("META-INF/resources/") != null) {
				this.classPath = new ClassPathResources(loader);
			}
		}
		else if (loader.getResourceAsStream("META-INF/resources/") != null) {
			this.classPath = new ClassPathResources(loader);
		}
	}

	// ---- the life of the context

	/** Refuses a registration once the context is initialized. */
	void open() {
		if (this.initialized) {
			throw new IllegalStateException("the context has been initialized");
		}
	}

	/**
	 * Initializes the application once what it is given and what it declares are
	 * registered: the listeners in their order, the filters in theirs, then the servlets
	 * loaded on startup by their numbers.
	 */
	void start() throws ServletException {
		ServletContextEvent event = new ServletContextEvent(this);
		// A listener may register more, listeners included.
		while (this.notified < this.contextListeners.size()) {
			this.contextListeners.get(this.notified++).contextInitialized(event);
		}
		// The default servlet where there is something to serve, made by where it serves
		// from: a program compiled ahead of time with nothing to serve holds none of it.
		Servlet served = this.documentRoot != null ? this.documentRoot.servlet(this)
				: this.classPath != null ? this.classPath.servlet(this) : null;
		if (served != null) {
			ServletRegistration.Dynamic registered = addServlet(DefaultServlet.NAME, served);
			if (registered != null) {
				// unless the application maps a servlet of its own there
				registered.addMapping("/");
			}
		}
		this.initialized = true;
		for (FilterEntry filter : this.filters.values()) {
			filter.filter.init(filter);
			this.initializedFilters.add(filter);
		}
		List<ServletEntry> eager = new ArrayList<>();
		for (ServletEntry servlet : this.servlets.values()) {
			if (servlet.loadOnStartup() >= 0) {
				int at = eager.size();
				while (at > 0 && eager.get(at - 1).loadOnStartup() > servlet.loadOnStartup()) {
					at--;
				}
				eager.add(at, servlet);
			}
		}
		for (ServletEntry servlet : eager) {
			servlet.servlet();
		}
	}

	void executor(Executor executor) {
		this.executor = executor;
	}

	void secure(boolean secure) {
		this.secure = secure;
	}

	boolean secure() {
		return this.secure;
	}

	ErrorPages errorPages() {
		open();
		if (this.errorPages == null) {
			this.errorPages = new ErrorPages(this);
		}
		return this.errorPages;
	}

	void maxFormSize(int bytes) {
		open();
		this.maxFormSize = bytes;
	}

	int maxFormSize() {
		return this.maxFormSize;
	}

	void log(BiConsumer<String, @Nullable Throwable> log) {
		open();
		this.log = log;
	}

	/**
	 * Runs a task of {@code AsyncContext.start}: the server's executor is set before it
	 * serves the first request.
	 */
	void execute(Runnable task) {
		Objects.requireNonNull(this.executor, "the server's executor").execute(task);
	}

	/**
	 * Ends the asynchronous requests still in hand once the server stopped taking
	 * requests: each ends as one that failed.
	 */
	void abort() {
		List<Async> aborted;
		synchronized (this.waiting) {
			this.stopping = true;
			aborted = new ArrayList<>(this.waiting);
		}
		for (Async async : aborted) {
			async.fail(new IOException("the server stopped"), true);
		}
	}

	/**
	 * Ends the sessions the server holds, then destroys what was initialized, in the
	 * reverse of the order it was made in.
	 */
	void stop() {
		Sessions made;
		Timeouts timing;
		synchronized (this) {
			made = this.sessions;
			timing = this.timeouts;
		}
		if (timing != null) {
			timing.stop();
		}
		if (made != null) {
			made.stop();
		}
		List<ServletEntry> all = new ArrayList<>(this.servlets.values());
		for (int i = all.size() - 1; i >= 0; i--) {
			try {
				all.get(i).destroy();
			}
			catch (RuntimeException ex) {
				log("the servlet " + all.get(i).getName() + " failed to be destroyed", ex);
			}
		}
		for (int i = this.initializedFilters.size() - 1; i >= 0; i--) {
			FilterEntry filter = this.initializedFilters.get(i);
			try {
				filter.filter.destroy();
			}
			catch (RuntimeException ex) {
				log("the filter " + filter.getName() + " failed to be destroyed", ex);
			}
		}
		this.initializedFilters.clear();
		ServletContextEvent event = new ServletContextEvent(this);
		while (this.notified > 0) {
			ServletContextListener listener = this.contextListeners.get(--this.notified);
			try {
				listener.contextDestroyed(event);
			}
			catch (RuntimeException ex) {
				log("a context listener failed", ex);
			}
		}
	}

	// ---- a request

	/**
	 * Answers an exchange. Nothing is thrown to the server but for a response that failed
	 * once committed, and the exchange is always answered and closed, so every transport
	 * of the server sees the same course. An asynchronous request is answered once its
	 * cycles completed it, by the thread that completes it: this one goes on.
	 */
	void handle(HttpExchange exchange) throws IOException {
		Response response = new Response(this, exchange);
		Async later = null;
		try {
			later = serve(exchange, response);
		}
		catch (Throwable ex) {
			fail(response, ex);
		}
		if (later != null) {
			// startAsync was called: the request's cycles go on from here, run by whoever
			// asks for them, and end it -- perhaps at once, on this thread
			later.begin();
			return;
		}
		close(exchange, response, true);
	}

	/**
	 * Ends the response and closes the exchange. A response that failed once committed is
	 * cut, not ended, where the thread the server called the handler on is there to: a
	 * JDK's server closes the connection of a handler that throws, so the client sees the
	 * body is not whole. Once the handler returned -- an asynchronous request ended by
	 * another thread -- the API has no other way to cut an exchange, and its body ends as
	 * it is.
	 * @param handler whether this is the thread the server called the handler on
	 */
	private void close(HttpExchange exchange, Response response, boolean handler) throws IOException {
		response.complete();
		if (response.broken() && handler) {
			throw new IOException("the response failed after it was committed");
		}
		exchange.close();
	}

	/**
	 * Serves a request: the cycles of the request once asynchronous, which then end it.
	 */
	private @Nullable Async serve(HttpExchange exchange, Response response) throws IOException, ServletException {
		String raw = exchange.getRequestURI().getRawPath();
		String path = UrlCodec.canonicalPath(raw);
		if (path == null || exchange.getRequestURI().getRawFragment() != null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return null;
		}
		int context = this.contextPath.length();
		if (context > 0) {
			// The server hands a context every path its own is a prefix of.
			if (!path.startsWith(this.contextPath) || (path.length() > context && path.charAt(context) != '/')) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return null;
			}
			if (path.length() == context && raw.equals(this.contextPath)) {
				// The context root asked without its slash: a relative link of its page
				// would leave the context.
				String query = exchange.getRequestURI().getRawQuery();
				response.sendRedirect(raw + "/" + (query == null ? "" : "?" + query));
				return null;
			}
			path = path.length() == context ? "/" : path.substring(context);
		}
		Match match = match(path);
		Request request = new Request(this, exchange, response, raw, match);
		List<ServletRequestListener> listeners = this.requestListeners;
		ServletRequestEvent event = listeners.isEmpty() ? null : new ServletRequestEvent(this, request);
		for (int i = 0; i < listeners.size(); i++) {
			listeners.get(i).requestInitialized(event);
		}
		Async async = null;
		Throwable thrown = null;
		try {
			if (match == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			}
			else {
				request.dispatched(path);
				request.dispatching(true);
				try {
					invoke(request, request, response, match, DispatcherType.REQUEST, path);
				}
				catch (Throwable ex) {
					thrown = ex;
				}
				finally {
					request.dispatching(false);
				}
				async = request.async();
				if (async == null && thrown != null) {
					fail(response, thrown);
				}
			}
		}
		catch (Throwable ex) {
			fail(response, ex);
		}
		finally {
			if (async == null) {
				end(request, response, event);
			}
		}
		if (async != null) {
			async.takeOver(thrown, handler -> ended(exchange, request, response, event, handler));
		}
		return async;
	}

	/** What ends a request: the error page an error asks for, its listeners. */
	private void end(Request request, Response response, @Nullable ServletRequestEvent event) {
		// the error page of an error the response answers, inside the request
		response.complete();
		// once the request is complete, its asynchronous cycles included
		List<ServletRequestListener> listeners = this.requestListeners;
		for (int i = listeners.size() - 1; i >= 0; i--) {
			listeners.get(i).requestDestroyed(event);
		}
		request.complete();
	}

	/**
	 * The end of an asynchronous request, once its cycles completed it: on the thread
	 * that completed it, which closes the exchange.
	 * @param handler whether this is the thread the server called the handler on
	 */
	private void ended(HttpExchange exchange, Request request, Response response, @Nullable ServletRequestEvent event,
			boolean handler) throws IOException {
		try {
			end(request, response, event);
		}
		catch (RuntimeException ex) {
			log("a request listener failed", ex);
		}
		close(exchange, response, handler);
	}

	/** Holds a cycle until its deadline, in {@code System.nanoTime()}'s count. */
	void arm(Async async, long deadline) {
		Timeouts timing;
		synchronized (this) {
			if (this.timeouts == null) {
				this.timeouts = new Timeouts();
			}
			timing = this.timeouts;
		}
		timing.arm(async, deadline);
	}

	/** Lets a cycle's deadline go. */
	void disarm(Async async) {
		Timeouts timing;
		synchronized (this) {
			timing = this.timeouts;
		}
		if (timing != null) {
			timing.disarm(async);
		}
	}

	/** An asynchronous request in hand: a stop ends it. */
	void hold(Async async) {
		boolean stopped;
		synchronized (this.waiting) {
			stopped = this.stopping;
			this.waiting.add(async);
		}
		// outside the monitor: what a failure runs may wait, and no monitor of the
		// container is held while a thread waits (a suspended thread holds none)
		if (stopped) {
			async.fail(new IOException("the server stopped"), true);
		}
	}

	void release(Async async) {
		synchronized (this.waiting) {
			this.waiting.remove(async);
		}
	}

	/**
	 * Runs the filters of a dispatch, then its servlet.
	 * @param base the request of this container the request handed on is or wraps
	 * @param path the path inside the context the filters are matched by, or null for a
	 * dispatch by name
	 */
	void invoke(Request base, ServletRequest request, ServletResponse response, Match match, DispatcherType type,
			@Nullable String path) throws IOException, ServletException {
		// A servlet that is not in service is found out before its filters run.
		match.servlet.servlet();
		List<FilterEntry> chain = null;
		List<FilterMapping> mappings = this.filterMappings;
		String name = match.servlet.getName();
		// The mappings by URL pattern in their order, then the ones by servlet name.
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < mappings.size(); i++) {
				FilterMapping mapping = mappings.get(i);
				if ((mapping.urlPatterns != null) == (pass == 0) && mapping.applies(type, path, name)) {
					if (chain == null) {
						chain = new ArrayList<>();
					}
					if (!chain.contains(mapping.filter)) {
						chain.add(mapping.filter);
					}
				}
			}
		}
		new Chain(chain, match.servlet, base).doFilter(request, response);
	}

	/**
	 * Answers for what a servlet or a filter threw, where an answer can still be given.
	 */
	void fail(Response response, Throwable ex) {
		if (ex instanceof UnavailableException unavailable) {
			if (response.sent()) {
				response.cut();
				return;
			}
			if (unavailable.isPermanent()) {
				response.fail(HttpServletResponse.SC_NOT_FOUND, null);
			}
			else {
				response.setIntHeader("Retry-After", unavailable.getUnavailableSeconds());
				response.fail(HttpServletResponse.SC_SERVICE_UNAVAILABLE, null);
			}
			return;
		}
		log("a request failed", ex);
		response.fail(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex);
	}

	/** The error pages, or null where the builder gave none. */
	@Nullable ErrorPages pages() {
		return this.errorPages;
	}

	/**
	 * The servlet of a path (Servlet 6.1, 12.1): the exact pattern, then the longest path
	 * prefix, then the extension, then the default servlet.
	 * @param path a canonical path inside the context, starting with a slash
	 * @return the match, or null where no servlet is mapped
	 */
	@Nullable Match match(String path) {
		Map<String, ServletEntry> patterns = this.patterns;
		if (path.equals("/")) {
			ServletEntry root = patterns.get("");
			if (root != null) {
				return new Match(root, "", "/", "", "", MappingMatch.CONTEXT_ROOT);
			}
		}
		else if (!path.endsWith("/*")) {
			ServletEntry exact = patterns.get(path);
			if (exact != null) {
				return new Match(exact, path, null, path.substring(1), path, MappingMatch.EXACT);
			}
		}
		String prefix = path;
		while (true) {
			String pattern = prefix + "/*";
			ServletEntry servlet = patterns.get(pattern);
			if (servlet != null) {
				boolean whole = prefix.length() == path.length();
				return new Match(servlet, prefix, whole ? null : path.substring(prefix.length()),
						whole ? "" : path.substring(prefix.length() + 1), pattern, MappingMatch.PATH);
			}
			if (prefix.isEmpty()) {
				break;
			}
			prefix = prefix.substring(0, prefix.lastIndexOf('/'));
		}
		int dot = path.lastIndexOf('.');
		if (dot > path.lastIndexOf('/')) {
			String pattern = "*" + path.substring(dot);
			ServletEntry servlet = patterns.get(pattern);
			if (servlet != null) {
				return new Match(servlet, path, null, path.substring(1, dot), pattern, MappingMatch.EXTENSION);
			}
		}
		ServletEntry servlet = patterns.get("/");
		if (servlet != null) {
			return new Match(servlet, path, null, "", "/", MappingMatch.DEFAULT);
		}
		return null;
	}

	// ---- registration

	/**
	 * Maps patterns to a servlet, all or none.
	 * @return the patterns another servlet already has
	 */
	Set<String> mapServlet(ServletEntry servlet, String[] urlPatterns) {
		Set<String> conflicts = new LinkedHashSet<>();
		for (String pattern : urlPatterns) {
			if (pattern == null || !(pattern.isEmpty() || pattern.startsWith("/") || pattern.startsWith("*."))) {
				throw new IllegalArgumentException("not a URL pattern: " + pattern);
			}
			ServletEntry mapped = this.patterns.get(pattern);
			if (mapped != null && mapped != servlet) {
				conflicts.add(pattern);
			}
		}
		if (conflicts.isEmpty()) {
			for (String pattern : urlPatterns) {
				this.patterns.put(pattern, servlet);
			}
		}
		return conflicts;
	}

	void mapFilter(FilterMapping mapping, boolean isMatchAfter) {
		if (isMatchAfter) {
			this.filterMappings.add(mapping);
		}
		else {
			this.filterMappings.add(this.filterMappingsBefore++, mapping);
		}
	}

	List<FilterMapping> filterMappings() {
		return this.filterMappings;
	}

	private static void named(String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("a registration has a name");
		}
	}

	@Override
	public ServletRegistration.@Nullable Dynamic addServlet(String servletName, Servlet servlet) {
		named(servletName);
		open();
		if (this.servlets.containsKey(servletName)) {
			return null;
		}
		for (ServletEntry registered : this.servlets.values()) {
			if (registered.instance == servlet) {
				throw new IllegalArgumentException("the servlet is registered as " + registered.getName());
			}
		}
		ServletEntry entry = new ServletEntry(this, servletName, servlet);
		this.servlets.put(servletName, entry);
		return entry;
	}

	@Override
	public ServletRegistration.@Nullable Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
		named(servletName);
		open();
		if (this.servlets.containsKey(servletName)) {
			return null;
		}
		try {
			return addServlet(servletName, createServlet(servletClass));
		}
		catch (ServletException ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
	}

	@Override
	public ServletRegistration.@Nullable Dynamic addServlet(String servletName, String className) {
		return addServlet(servletName, Instances.type(className, Servlet.class));
	}

	@Override
	public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
		throw new UnsupportedOperationException("JSP files are not supported");
	}

	@Override
	public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
		return Instances.create(clazz);
	}

	@Override
	public @Nullable ServletRegistration getServletRegistration(String servletName) {
		return this.servlets.get(servletName);
	}

	@Override
	public Map<String, ? extends ServletRegistration> getServletRegistrations() {
		return Collections.unmodifiableMap(this.servlets);
	}

	@Override
	public FilterRegistration.@Nullable Dynamic addFilter(String filterName, Filter filter) {
		named(filterName);
		open();
		if (this.filters.containsKey(filterName)) {
			return null;
		}
		FilterEntry entry = new FilterEntry(this, filterName, filter);
		this.filters.put(filterName, entry);
		return entry;
	}

	@Override
	public FilterRegistration.@Nullable Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
		named(filterName);
		open();
		if (this.filters.containsKey(filterName)) {
			return null;
		}
		try {
			return addFilter(filterName, createFilter(filterClass));
		}
		catch (ServletException ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
	}

	@Override
	public FilterRegistration.@Nullable Dynamic addFilter(String filterName, String className) {
		return addFilter(filterName, Instances.type(className, Filter.class));
	}

	@Override
	public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
		return Instances.create(clazz);
	}

	@Override
	public @Nullable FilterRegistration getFilterRegistration(String filterName) {
		return this.filters.get(filterName);
	}

	@Override
	public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
		return Collections.unmodifiableMap(this.filters);
	}

	@Override
	public <T extends EventListener> void addListener(T t) {
		open();
		boolean taken = false;
		if (t instanceof ServletContextListener) {
			this.contextListeners.add((ServletContextListener) t);
			taken = true;
		}
		if (t instanceof ServletContextAttributeListener) {
			this.attributeListeners.add((ServletContextAttributeListener) t);
			taken = true;
		}
		if (t instanceof ServletRequestListener) {
			this.requestListeners.add((ServletRequestListener) t);
			taken = true;
		}
		if (t instanceof ServletRequestAttributeListener) {
			this.requestAttributeListeners.add((ServletRequestAttributeListener) t);
			taken = true;
		}
		if (t instanceof HttpSessionListener) {
			this.sessionListeners.add((HttpSessionListener) t);
			taken = true;
		}
		if (t instanceof HttpSessionAttributeListener) {
			this.sessionAttributeListeners.add((HttpSessionAttributeListener) t);
			taken = true;
		}
		if (t instanceof HttpSessionIdListener) {
			this.sessionIdListeners.add((HttpSessionIdListener) t);
			taken = true;
		}
		if (!taken) {
			throw new IllegalArgumentException("not a listener of the context, a request or a session: "
					+ (t == null ? null : t.getClass().getName()));
		}
	}

	@Override
	public void addListener(Class<? extends EventListener> listenerClass) {
		try {
			addListener(createListener(listenerClass));
		}
		catch (ServletException ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
	}

	@Override
	public void addListener(String className) {
		addListener(Instances.type(className, EventListener.class));
	}

	@Override
	public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
		return Instances.create(clazz);
	}

	// ---- what the context answers

	@Override
	public String getContextPath() {
		return this.contextPath;
	}

	@Override
	public @Nullable ServletContext getContext(@Nullable String uripath) {
		if (uripath == null || !uripath.startsWith("/")) {
			return null;
		}
		int context = this.contextPath.length();
		return uripath.startsWith(this.contextPath) && (uripath.length() == context || uripath.charAt(context) == '/')
				? this : null;
	}

	@Override
	public int getMajorVersion() {
		return 6;
	}

	@Override
	public int getMinorVersion() {
		return 1;
	}

	@Override
	public int getEffectiveMajorVersion() {
		return 6;
	}

	@Override
	public int getEffectiveMinorVersion() {
		return 1;
	}

	@Override
	public @Nullable String getMimeType(@Nullable String file) {
		return file == null ? null : MimeTypes.of(file);
	}

	/** Sets the directory whose files are the application's first. */
	void documentRoot(Path directory) {
		if (directory == null) {
			throw new NullPointerException("directory");
		}
		open();
		this.documentRoot = new DocumentRoot(directory);
	}

	/**
	 * The resource of a path: a file or a directory of the document root, else of the
	 * class path's {@code META-INF/resources}.
	 * @param path a normalized path starting with a slash
	 * @return what is there, or null for nothing
	 */
	@Nullable Resource resource(String path) throws IOException {
		Resource found = this.documentRoot == null ? null : this.documentRoot.find(path);
		return found != null || this.classPath == null ? found : this.classPath.find(path);
	}

	@Override
	public @Nullable Set<String> getResourcePaths(@Nullable String path) {
		String directory = path == null || !path.startsWith("/") ? null : Resource.normalized(path);
		if (directory == null) {
			return null;
		}
		if (!directory.endsWith("/")) {
			directory += "/";
		}
		Set<String> paths = new TreeSet<>();
		try {
			if (this.documentRoot != null) {
				List<String> names = this.documentRoot.names(directory);
				for (int i = 0; names != null && i < names.size(); i++) {
					paths.add(directory + names.get(i));
				}
			}
			if (this.classPath != null) {
				List<String> names = this.classPath.names(directory);
				for (int i = 0; i < names.size(); i++) {
					paths.add(directory + names.get(i));
				}
			}
		}
		catch (IOException ex) {
			log("the resources of " + directory + " could not be listed", ex);
		}
		return paths.isEmpty() ? null : paths;
	}

	@Override
	public @Nullable URL getResource(@Nullable String path) throws MalformedURLException {
		if (path == null || !path.startsWith("/")) {
			throw new MalformedURLException("the path of a resource starts with a slash: " + path);
		}
		String normal = Resource.normalized(path);
		if (normal == null) {
			return null;
		}
		URL found = this.documentRoot == null ? null : this.documentRoot.url(normal);
		return found != null || this.classPath == null ? found : this.classPath.url(normal);
	}

	@Override
	public @Nullable InputStream getResourceAsStream(@Nullable String path) {
		String normal = path == null || !path.startsWith("/") ? null : Resource.normalized(path);
		if (normal == null) {
			return null;
		}
		try {
			InputStream found = this.documentRoot == null ? null : this.documentRoot.stream(normal);
			return found != null || this.classPath == null ? found : this.classPath.stream(normal);
		}
		catch (IOException ex) {
			return null;
		}
	}

	@Override
	public @Nullable RequestDispatcher getRequestDispatcher(@Nullable String path) {
		if (path == null || !path.startsWith("/")) {
			return null;
		}
		int question = path.indexOf('?');
		String query = question < 0 ? null : path.substring(question + 1);
		String canonical = UrlCodec.canonicalPath(question < 0 ? path : path.substring(0, question));
		if (canonical == null) {
			return null;
		}
		Match match = match(canonical);
		return match == null ? null : new Dispatcher(this, match, canonical, query);
	}

	@Override
	public @Nullable RequestDispatcher getNamedDispatcher(String name) {
		ServletEntry servlet = this.servlets.get(name);
		return servlet == null ? null
				: new Dispatcher(this, new Match(servlet, null, null, null, null, null), null, null);
	}

	@Override
	public void log(String msg) {
		BiConsumer<String, @Nullable Throwable> log = this.log;
		if (log == null) {
			System.err.println(msg);
		}
		else {
			log.accept(msg, null);
		}
	}

	@Override
	public void log(String message, Throwable throwable) {
		BiConsumer<String, @Nullable Throwable> log = this.log;
		if (log == null) {
			System.err.println(message + ": " + throwable);
		}
		else {
			log.accept(message, throwable);
		}
	}

	@Override
	public @Nullable String getRealPath(@Nullable String path) {
		if (this.documentRoot == null || path == null) {
			return null;
		}
		String normal = Resource.normalized(path.startsWith("/") ? path : "/" + path);
		return normal == null ? null : this.documentRoot.realPath(normal);
	}

	@Override
	public String getServerInfo() {
		return SERVER_INFO;
	}

	@Override
	public @Nullable String getInitParameter(String name) {
		if (name == null) {
			throw new NullPointerException("name");
		}
		return this.initParameters.get(name);
	}

	@Override
	public Enumeration<String> getInitParameterNames() {
		return Collections.enumeration(new ArrayList<>(this.initParameters.keySet()));
	}

	@Override
	public boolean setInitParameter(String name, String value) {
		if (name == null) {
			throw new NullPointerException("name");
		}
		open();
		if (this.initParameters.containsKey(name)) {
			return false;
		}
		this.initParameters.put(name, value);
		return true;
	}

	@Override
	public @Nullable Object getAttribute(String name) {
		if (name == null) {
			throw new NullPointerException("name");
		}
		return this.attributes.get(name);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		return Collections.enumeration(new ArrayList<>(this.attributes.keySet()));
	}

	@Override
	public void setAttribute(String name, Object object) {
		if (name == null) {
			throw new NullPointerException("name");
		}
		if (object == null) {
			removeAttribute(name);
			return;
		}
		Object old = this.attributes.put(name, object);
		List<ServletContextAttributeListener> listeners = this.attributeListeners;
		for (int i = 0; i < listeners.size(); i++) {
			if (old == null) {
				listeners.get(i).attributeAdded(new ServletContextAttributeEvent(this, name, object));
			}
			else {
				listeners.get(i).attributeReplaced(new ServletContextAttributeEvent(this, name, old));
			}
		}
	}

	@Override
	public void removeAttribute(String name) {
		Object old = this.attributes.remove(name);
		List<ServletContextAttributeListener> listeners = this.attributeListeners;
		for (int i = 0; old != null && i < listeners.size(); i++) {
			listeners.get(i).attributeRemoved(new ServletContextAttributeEvent(this, name, old));
		}
	}

	@Override
	public String getServletContextName() {
		return this.contextPath.isEmpty() ? "ROOT" : this.contextPath.substring(1);
	}

	// ---- sessions

	/** Sets where the sessions are kept, until the server starts. */
	void setSessionStore(SessionStore store) {
		open();
		this.sessionStore = store;
	}

	/**
	 * The sessions, made by the first request that asks for one: in the store the server
	 * was given, else the first the class loader's {@code SessionStore} providers name,
	 * else in memory.
	 */
	synchronized Sessions sessions() {
		if (this.sessions == null) {
			SessionStore store = this.sessionStore;
			if (store == null) {
				store = ServiceLoader.load(SessionStore.class, this.loader)
					.findFirst()
					.orElseGet(MemorySessionStore::new);
			}
			this.sessions = new Sessions(this, store);
		}
		return this.sessions;
	}

	synchronized CookieConfig cookieConfig() {
		if (this.cookieConfig == null) {
			this.cookieConfig = new CookieConfig(this);
		}
		return this.cookieConfig;
	}

	boolean tracks(SessionTrackingMode mode) {
		return mode == SessionTrackingMode.COOKIE ? this.trackCookies
				: mode == SessionTrackingMode.URL && this.trackUrls;
	}

	@Override
	public SessionCookieConfig getSessionCookieConfig() {
		return cookieConfig();
	}

	@Override
	public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
		open();
		trackable(sessionTrackingModes);
		this.trackCookies = sessionTrackingModes.contains(SessionTrackingMode.COOKIE);
		this.trackUrls = sessionTrackingModes.contains(SessionTrackingMode.URL);
	}

	/** Refuses the modes a session cannot be tracked by here. */
	static void trackable(Set<SessionTrackingMode> modes) {
		if (modes.contains(SessionTrackingMode.SSL)) {
			throw new IllegalArgumentException("sessions are not tracked by SSL");
		}
	}

	@Override
	public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
		return EnumSet.of(SessionTrackingMode.COOKIE);
	}

	@Override
	public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
		Set<SessionTrackingMode> modes = EnumSet.noneOf(SessionTrackingMode.class);
		if (this.trackCookies) {
			modes.add(SessionTrackingMode.COOKIE);
		}
		if (this.trackUrls) {
			modes.add(SessionTrackingMode.URL);
		}
		return modes;
	}

	@Override
	public @Nullable JspConfigDescriptor getJspConfigDescriptor() {
		return null;
	}

	@Override
	public ClassLoader getClassLoader() {
		return this.loader;
	}

	@Override
	public void declareRoles(String... roleNames) {
		open();
	}

	@Override
	public String getVirtualServerName() {
		return "localhost";
	}

	@Override
	public int getSessionTimeout() {
		return this.sessionTimeout;
	}

	@Override
	public void setSessionTimeout(int sessionTimeout) {
		open();
		this.sessionTimeout = sessionTimeout;
	}

	@Override
	public @Nullable String getRequestCharacterEncoding() {
		return this.requestCharacterEncoding;
	}

	@Override
	public void setRequestCharacterEncoding(@Nullable String encoding) {
		open();
		this.requestCharacterEncoding = encoding == null ? null : Charset.forName(encoding).name();
	}

	@Override
	public @Nullable String getResponseCharacterEncoding() {
		return this.responseCharacterEncoding;
	}

	@Override
	public void setResponseCharacterEncoding(@Nullable String encoding) {
		open();
		this.responseCharacterEncoding = encoding == null ? null : Charset.forName(encoding).name();
	}

}
