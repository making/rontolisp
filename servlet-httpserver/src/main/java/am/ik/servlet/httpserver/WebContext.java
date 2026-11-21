package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
import jakarta.servlet.http.MappingMatch;

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

	private final List<FilterEntry> initializedFilters = new ArrayList<>();

	private int notified;

	private volatile boolean initialized;

	private String requestCharacterEncoding;

	private String responseCharacterEncoding;

	private int sessionTimeout = 30;

	WebContext(String contextPath) {
		this.contextPath = contextPath;
	}

	// ---- the life of the context

	/** Refuses a registration once the context is initialized. */
	void open() {
		if (this.initialized) {
			throw new IllegalStateException("the context has been initialized");
		}
	}

	/**
	 * Initializes the application: the listeners in their order, the filters in theirs,
	 * then the servlets loaded on startup by their numbers.
	 */
	void start() throws ServletException {
		ServletContextEvent event = new ServletContextEvent(this);
		// A listener may register more, listeners included.
		while (this.notified < this.contextListeners.size()) {
			this.contextListeners.get(this.notified++).contextInitialized(event);
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

	/** Destroys what was initialized, in the reverse of the order it was made in. */
	void stop() {
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
	 * Answers an exchange. Nothing is thrown to the server and the exchange is always
	 * answered and closed, so every transport of the server sees the same course.
	 */
	void handle(HttpExchange exchange) {
		Response response = new Response(this, exchange);
		try {
			serve(exchange, response);
		}
		catch (Throwable ex) {
			fail(response, ex);
		}
		finally {
			response.complete();
			exchange.close();
		}
	}

	private void serve(HttpExchange exchange, Response response) throws IOException, ServletException {
		String raw = exchange.getRequestURI().getRawPath();
		String path = UrlCodec.canonicalPath(raw);
		if (path == null || exchange.getRequestURI().getRawFragment() != null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		int context = this.contextPath.length();
		if (context > 0) {
			// The server hands a context every path its own is a prefix of.
			if (!path.startsWith(this.contextPath) || (path.length() > context && path.charAt(context) != '/')) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			if (path.length() == context && raw.equals(this.contextPath)) {
				// The context root asked without its slash: a relative link of its page
				// would leave the context.
				String query = exchange.getRequestURI().getRawQuery();
				response.sendRedirect(raw + "/" + (query == null ? "" : "?" + query));
				return;
			}
			path = path.length() == context ? "/" : path.substring(context);
		}
		Match match = match(path);
		Request request = new Request(this, exchange, raw, match);
		List<ServletRequestListener> listeners = this.requestListeners;
		ServletRequestEvent event = listeners.isEmpty() ? null : new ServletRequestEvent(this, request);
		for (int i = 0; i < listeners.size(); i++) {
			listeners.get(i).requestInitialized(event);
		}
		try {
			if (match == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			}
			else {
				invoke(request, response, match, DispatcherType.REQUEST, path);
			}
		}
		catch (Throwable ex) {
			fail(response, ex);
		}
		finally {
			for (int i = listeners.size() - 1; i >= 0; i--) {
				listeners.get(i).requestDestroyed(event);
			}
		}
	}

	/**
	 * Runs the filters of a dispatch, then its servlet.
	 * @param path the path inside the context the filters are matched by, or null for a
	 * dispatch by name
	 */
	void invoke(ServletRequest request, ServletResponse response, Match match, DispatcherType type, String path)
			throws IOException, ServletException {
		// A servlet that is not in service is found out before its filters run.
		match.servlet.servlet();
		List<Filter> chain = null;
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
					if (!chain.contains(mapping.filter.filter)) {
						chain.add(mapping.filter.filter);
					}
				}
			}
		}
		new Chain(chain, match.servlet).doFilter(request, response);
	}

	/**
	 * Answers for what a servlet or a filter threw, where an answer can still be given.
	 */
	private void fail(Response response, Throwable ex) {
		if (ex instanceof UnavailableException) {
			UnavailableException unavailable = (UnavailableException) ex;
			if (unavailable.isPermanent()) {
				response.fail(HttpServletResponse.SC_NOT_FOUND);
			}
			else {
				response.setIntHeader("Retry-After", unavailable.getUnavailableSeconds());
				response.fail(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
			return;
		}
		log("a request failed", ex);
		response.fail(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
	}

	/**
	 * The servlet of a path (Servlet 6.1, 12.1): the exact pattern, then the longest path
	 * prefix, then the extension, then the default servlet.
	 * @param path a canonical path inside the context, starting with a slash
	 * @return the match, or null where no servlet is mapped
	 */
	Match match(String path) {
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
	public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
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
	public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
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
	public ServletRegistration.Dynamic addServlet(String servletName, String className) {
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
	public ServletRegistration getServletRegistration(String servletName) {
		return this.servlets.get(servletName);
	}

	@Override
	public Map<String, ? extends ServletRegistration> getServletRegistrations() {
		return Collections.unmodifiableMap(this.servlets);
	}

	@Override
	public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
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
	public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
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
	public FilterRegistration.Dynamic addFilter(String filterName, String className) {
		return addFilter(filterName, Instances.type(className, Filter.class));
	}

	@Override
	public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
		return Instances.create(clazz);
	}

	@Override
	public FilterRegistration getFilterRegistration(String filterName) {
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
		if (!taken) {
			// The listeners of a session have nothing to hear: there are no sessions.
			throw new IllegalArgumentException(
					"not a listener of the context or of a request: " + (t == null ? null : t.getClass().getName()));
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
	public ServletContext getContext(String uripath) {
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
	public String getMimeType(String file) {
		return file == null ? null : MimeTypes.of(file);
	}

	@Override
	public Set<String> getResourcePaths(String path) {
		return null;
	}

	@Override
	public URL getResource(String path) {
		return null;
	}

	@Override
	public InputStream getResourceAsStream(String path) {
		return null;
	}

	@Override
	public RequestDispatcher getRequestDispatcher(String path) {
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
	public RequestDispatcher getNamedDispatcher(String name) {
		ServletEntry servlet = this.servlets.get(name);
		return servlet == null ? null
				: new Dispatcher(this, new Match(servlet, null, null, null, null, null), null, null);
	}

	@Override
	public void log(String msg) {
		System.err.println(msg);
	}

	@Override
	public void log(String message, Throwable throwable) {
		System.err.println(message + ": " + throwable);
	}

	@Override
	public String getRealPath(String path) {
		return null;
	}

	@Override
	public String getServerInfo() {
		return SERVER_INFO;
	}

	@Override
	public String getInitParameter(String name) {
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
	public Object getAttribute(String name) {
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

	@Override
	public SessionCookieConfig getSessionCookieConfig() {
		throw new UnsupportedOperationException("sessions are not supported");
	}

	@Override
	public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
		throw new UnsupportedOperationException("sessions are not supported");
	}

	@Override
	public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
		return Collections.emptySet();
	}

	@Override
	public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
		return Collections.emptySet();
	}

	@Override
	public JspConfigDescriptor getJspConfigDescriptor() {
		return null;
	}

	@Override
	public ClassLoader getClassLoader() {
		return WebContext.class.getClassLoader();
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
	public String getRequestCharacterEncoding() {
		return this.requestCharacterEncoding;
	}

	@Override
	public void setRequestCharacterEncoding(String encoding) {
		open();
		this.requestCharacterEncoding = encoding == null ? null : Charset.forName(encoding).name();
	}

	@Override
	public String getResponseCharacterEncoding() {
		return this.responseCharacterEncoding;
	}

	@Override
	public void setResponseCharacterEncoding(String encoding) {
		open();
		this.responseCharacterEncoding = encoding == null ? null : Charset.forName(encoding).name();
	}

}
