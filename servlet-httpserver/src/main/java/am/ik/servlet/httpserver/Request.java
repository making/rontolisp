package am.ik.servlet.httpserver;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import org.jspecify.annotations.Nullable;

/**
 * A request over an exchange. One thread reads it; a dispatch changes the path it answers
 * and puts the query of its target in front of the parameters, and gives both back when
 * it returns. An asynchronous dispatch changes them for good.
 */
final class Request implements HttpServletRequest {

	private static final AtomicLong IDS = new AtomicLong();

	/** The path parameter a session's id is carried by in a URL. */
	private static final String PATH_PARAMETER = "jsessionid";

	private final WebContext context;

	private final HttpExchange exchange;

	private final Response response;

	final Map<String, Object> attributes = new LinkedHashMap<>();

	private String requestUri;

	private @Nullable String queryString;

	private @Nullable Match match;

	private DispatcherType dispatcherType = DispatcherType.REQUEST;

	private @Nullable String characterEncoding;

	private @Nullable RequestBody input;

	private boolean streamTaken;

	private @Nullable BufferedReader reader;

	private @Nullable Map<String, List<String>> ownParameters;

	private @Nullable List<Map<String, List<String>>> dispatchParameters;

	private @Nullable Map<String, String[]> parameterMap;

	/** The parts of a multipart body, once read ({@link Multipart#parts}). */
	@Nullable List<FormPart> parts;

	/** Why the parts of a multipart body could not be read: thrown again when asked. */
	@Nullable Exception partsFailure;

	private Cookie @Nullable [] cookies;

	private boolean cookiesRead;

	private @Nullable String id;

	private @Nullable Session session;

	private boolean sessionLooked;

	private boolean requestedRead;

	private @Nullable String requestedSessionId;

	private boolean requestedFromCookie;

	/**
	 * Whether the filters and the servlet the request is inside of all support
	 * asynchronous requests: each element of a chain narrows it while it runs.
	 */
	boolean asyncSupported = true;

	/** Whether a dispatch of the container runs: startAsync is called inside one. */
	private boolean dispatching;

	private @Nullable Async async;

	/**
	 * The path inside the context of the dispatch of the container that ran last: with
	 * the servlet and the URI the request answers once it returned, where
	 * {@code dispatch()} goes.
	 */
	private @Nullable String dispatchedPath;

	/**
	 * A request.
	 * @param requestUri the path as the request line has it
	 * @param match the servlet the path is mapped to, or null where none is: only a
	 * listener sees such a request
	 */
	Request(WebContext context, HttpExchange exchange, Response response, String requestUri, @Nullable Match match) {
		this.context = context;
		this.exchange = exchange;
		this.response = response;
		response.request = this;
		this.requestUri = requestUri;
		this.queryString = exchange.getRequestURI().getRawQuery();
		this.match = match;
	}

	// ---- dispatch

	/** What a dispatch changes of a request, kept to be given back. */
	record Target(String requestUri, @Nullable String queryString, @Nullable Match match,
			DispatcherType dispatcherType) {
	}

	/**
	 * Turns the request to the target of a dispatch.
	 * @param match the target's mapping, or null to keep the path (an include, a dispatch
	 * by name)
	 * @param path the target's path inside the context
	 * @param query the target's query string, or null
	 * @param type the kind of dispatch
	 * @return what the request answered until now
	 */
	Target dispatch(@Nullable Match match, @Nullable String path, @Nullable String query, DispatcherType type) {
		Target before = new Target(this.requestUri, this.queryString, this.match, this.dispatcherType);
		this.dispatcherType = type;
		if (match != null) {
			this.match = match;
			this.requestUri = this.context.getContextPath() + path;
			if (query != null) {
				this.queryString = query;
			}
		}
		if (query != null) {
			Map<String, List<String>> parameters = new LinkedHashMap<>();
			UrlCodec.parameters(query, StandardCharsets.UTF_8, parameters);
			if (this.dispatchParameters == null) {
				this.dispatchParameters = new ArrayList<>();
			}
			this.dispatchParameters.add(parameters);
			this.parameterMap = null;
		}
		return before;
	}

	/** Gives back what a dispatch changed. */
	void restore(Target before, boolean hadQuery) {
		this.requestUri = before.requestUri();
		this.queryString = before.queryString();
		this.match = before.match();
		this.dispatcherType = before.dispatcherType();
		List<Map<String, List<String>>> dispatched = this.dispatchParameters;
		if (hadQuery && dispatched != null) {
			dispatched.removeLast();
			this.parameterMap = null;
		}
	}

	/**
	 * Whether a dispatch of the container, the request's own or an asynchronous one,
	 * runs.
	 */
	void dispatching(boolean running) {
		this.dispatching = running;
	}

	/** The request's own dispatch, to the servlet of its path. */
	void dispatched(String path) {
		this.dispatchedPath = path;
	}

	/**
	 * Where the container dispatched last, asked once that dispatch returned: a forward
	 * inside it gave its path back.
	 */
	Async.Target lastDispatched() {
		return new Async.Target(this.match, this.dispatchedPath, this.requestUri, null);
	}

	/**
	 * Turns the request to the target of an asynchronous dispatch for good. A query of
	 * the target's own is put in front of the parameters.
	 */
	void dispatchAsync(Async.Target target) {
		this.dispatcherType = DispatcherType.ASYNC;
		this.match = target.match();
		this.requestUri = target.requestUri();
		String query = target.query();
		if (query != null && !query.equals(this.queryString)) {
			this.queryString = query;
			Map<String, List<String>> parameters = new LinkedHashMap<>();
			UrlCodec.parameters(query, StandardCharsets.UTF_8, parameters);
			if (this.dispatchParameters == null) {
				this.dispatchParameters = new ArrayList<>();
			}
			this.dispatchParameters.add(parameters);
			this.parameterMap = null;
		}
		this.dispatchedPath = target.path();
	}

	/**
	 * Tells the targets of asynchronous dispatches where the request went first: set by
	 * the first, from the path it answers then (Servlet 6.1, 9.7.2).
	 */
	void asyncAttributes() {
		if (!this.attributes.containsKey(AsyncContext.ASYNC_REQUEST_URI)) {
			Object[] values = { getRequestURI(), getContextPath(), getServletPath(), getPathInfo(), getQueryString(),
					getHttpServletMapping() };
			String[] names = { AsyncContext.ASYNC_REQUEST_URI, AsyncContext.ASYNC_CONTEXT_PATH,
					AsyncContext.ASYNC_SERVLET_PATH, AsyncContext.ASYNC_PATH_INFO, AsyncContext.ASYNC_QUERY_STRING,
					AsyncContext.ASYNC_MAPPING };
			for (int i = 0; i < names.length; i++) {
				if (values[i] != null) {
					this.attributes.put(names[i], values[i]);
				}
			}
		}
	}

	@Nullable Async async() {
		return this.async;
	}

	/** The name of the servlet the request is mapped to, or null. */
	@Nullable String servletName() {
		Match match = this.match;
		return match == null ? null : match.servlet.getName();
	}

	/** The request of this container a servlet's request is, through its wrappers. */
	static Request of(ServletRequest request) {
		ServletRequest current = request;
		while (current instanceof jakarta.servlet.ServletRequestWrapper wrapper) {
			current = wrapper.getRequest();
		}
		if (current instanceof Request own) {
			return own;
		}
		throw new IllegalArgumentException("not a request of this container, nor a wrapper of one");
	}

	// ---- the request line

	@Override
	public String getMethod() {
		// An error page is asked for by GET, the request's own method an attribute
		// (Servlet 6.1), as Tomcat and Jetty do for a HEAD too.
		return this.dispatcherType == DispatcherType.ERROR ? "GET" : this.exchange.getRequestMethod();
	}

	@Override
	public String getRequestURI() {
		return this.requestUri;
	}

	@Override
	public StringBuffer getRequestURL() {
		StringBuffer url = new StringBuffer();
		String scheme = getScheme();
		int port = getServerPort();
		url.append(scheme).append("://").append(getServerName());
		if (port > 0 && port != (scheme.equals("https") ? 443 : 80)) {
			url.append(':').append(port);
		}
		return url.append(this.requestUri);
	}

	@Override
	public String getContextPath() {
		return this.context.getContextPath();
	}

	@Override
	public String getServletPath() {
		// a request's own is a path's match, which has one
		Match match = this.match;
		return match == null || match.servletPath == null ? "" : match.servletPath;
	}

	@Override
	public @Nullable String getPathInfo() {
		return this.match == null ? null : this.match.pathInfo;
	}

	@Override
	public @Nullable String getPathTranslated() {
		return null;
	}

	@Override
	public @Nullable String getQueryString() {
		return this.queryString;
	}

	@Override
	public @Nullable HttpServletMapping getHttpServletMapping() {
		return this.match;
	}

	@Override
	public String getProtocol() {
		return this.exchange.getProtocol();
	}

	@Override
	public String getScheme() {
		return this.context.secure() ? "https" : "http";
	}

	@Override
	public boolean isSecure() {
		return this.context.secure();
	}

	@Override
	public String getServerName() {
		String host = getHeader("Host");
		if (host == null || host.isEmpty()) {
			return getLocalName();
		}
		int colon = host.indexOf(':', host.indexOf(']') + 1);
		return colon < 0 ? host : host.substring(0, colon);
	}

	@Override
	public int getServerPort() {
		String host = getHeader("Host");
		if (host == null || host.isEmpty()) {
			return getLocalPort();
		}
		int colon = host.indexOf(':', host.indexOf(']') + 1);
		int scheme = this.context.secure() ? 443 : 80;
		if (colon < 0) {
			return scheme;
		}
		try {
			return Integer.parseInt(host.substring(colon + 1));
		}
		catch (NumberFormatException ex) {
			return scheme;
		}
	}

	@Override
	public String getRemoteAddr() {
		return address(this.exchange.getRemoteAddress());
	}

	@Override
	public String getRemoteHost() {
		return address(this.exchange.getRemoteAddress());
	}

	@Override
	public int getRemotePort() {
		return this.exchange.getRemoteAddress().getPort();
	}

	@Override
	public String getLocalName() {
		return this.exchange.getLocalAddress().getHostString();
	}

	@Override
	public String getLocalAddr() {
		return address(this.exchange.getLocalAddress());
	}

	@Override
	public int getLocalPort() {
		return this.exchange.getLocalAddress().getPort();
	}

	private static String address(InetSocketAddress address) {
		return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
	}

	// ---- header fields

	@Override
	public String getHeader(String name) {
		return this.exchange.getRequestHeaders().getFirst(name);
	}

	@Override
	public Enumeration<String> getHeaders(String name) {
		List<String> values = this.exchange.getRequestHeaders().get(name);
		return Collections.enumeration(values == null ? Collections.<String>emptyList() : values);
	}

	@Override
	public Enumeration<String> getHeaderNames() {
		return Collections.enumeration(this.exchange.getRequestHeaders().keySet());
	}

	@Override
	public int getIntHeader(String name) {
		String value = getHeader(name);
		return value == null ? -1 : Integer.parseInt(value);
	}

	@Override
	public long getDateHeader(String name) {
		String value = getHeader(name);
		if (value == null) {
			return -1L;
		}
		long millis = HttpDates.parse(value);
		if (millis < 0) {
			throw new IllegalArgumentException("not a date: " + value);
		}
		return millis;
	}

	@Override
	public Cookie @Nullable [] getCookies() {
		if (!this.cookiesRead) {
			this.cookiesRead = true;
			List<String> fields = this.exchange.getRequestHeaders().get("Cookie");
			if (fields != null) {
				List<Cookie> all = new ArrayList<>();
				for (String field : fields) {
					Fields.cookies(field, all);
				}
				if (!all.isEmpty()) {
					this.cookies = all.toArray(new Cookie[0]);
				}
			}
		}
		return this.cookies == null ? null : this.cookies.clone();
	}

	@Override
	public Locale getLocale() {
		return Fields.locales(this.exchange.getRequestHeaders().get("Accept-Language")).getFirst();
	}

	@Override
	public Enumeration<Locale> getLocales() {
		return Collections.enumeration(Fields.locales(this.exchange.getRequestHeaders().get("Accept-Language")));
	}

	// ---- the body

	@Override
	public @Nullable String getCharacterEncoding() {
		if (this.characterEncoding != null) {
			return this.characterEncoding;
		}
		String charset = Fields.parameter(getContentType(), "charset");
		return charset != null ? charset : this.context.getRequestCharacterEncoding();
	}

	@Override
	public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
		if (this.reader != null || this.ownParameters != null || this.parts != null) {
			return;
		}
		if (encoding != null && charset(encoding) == null) {
			throw new UnsupportedEncodingException(encoding);
		}
		this.characterEncoding = encoding;
	}

	private static @Nullable Charset charset(String name) {
		try {
			return Charset.forName(name);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	@Override
	public int getContentLength() {
		long length = getContentLengthLong();
		return length > Integer.MAX_VALUE ? -1 : (int) length;
	}

	@Override
	public long getContentLengthLong() {
		String value = getHeader("Content-Length");
		if (value == null) {
			return -1L;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex) {
			return -1L;
		}
	}

	@Override
	public String getContentType() {
		return getHeader("Content-Type");
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		if (this.reader != null) {
			throw new IllegalStateException("getReader() has been called for this request");
		}
		this.streamTaken = true;
		return body();
	}

	@Override
	public BufferedReader getReader() throws IOException {
		if (this.reader == null) {
			if (this.streamTaken) {
				throw new IllegalStateException("getInputStream() has been called for this request");
			}
			String encoding = getCharacterEncoding();
			Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : charset(encoding);
			if (charset == null) {
				throw new UnsupportedEncodingException(encoding);
			}
			this.reader = new BufferedReader(new InputStreamReader(body(), charset));
		}
		return this.reader;
	}

	/**
	 * The body: of the length its field declares; of chunks, read to their end; else none
	 * (RFC 9112, 6.3).
	 */
	RequestBody body() {
		if (this.input == null) {
			long length = getHeader("Transfer-Encoding") != null ? -1 : Math.max(getContentLengthLong(), 0);
			this.input = new RequestBody(this, this.exchange.getRequestBody(), length);
		}
		return this.input;
	}

	// ---- parameters

	/** The parameters of the request itself: the query string's, then a form body's. */
	private Map<String, List<String>> ownParameters() {
		if (this.ownParameters == null) {
			Map<String, List<String>> parameters = new LinkedHashMap<>();
			String query = this.exchange.getRequestURI().getRawQuery();
			if (query != null) {
				UrlCodec.parameters(query, StandardCharsets.UTF_8, parameters);
			}
			if (!this.streamTaken && this.reader == null && "POST".equals(getMethod()) && form(getContentType())) {
				UrlCodec.parameters(formBody(), bodyCharset(), parameters);
			}
			else if (!this.streamTaken && this.reader == null && this.match != null
					&& this.match.servlet.multipart != null) {
				// the parts with no file name, of any method (Servlet 6.1, 3.2)
				this.match.servlet.multipart.parameters(this, parameters);
			}
			this.ownParameters = parameters;
		}
		return this.ownParameters;
	}

	/** The charset of a form's or a multipart body's text: the request's, else UTF-8. */
	Charset bodyCharset() {
		String encoding = getCharacterEncoding();
		Charset charset = encoding == null ? null : charset(encoding);
		return charset == null ? StandardCharsets.UTF_8 : charset;
	}

	static boolean multipart(String contentType) {
		return "multipart/form-data".equals(MultipartBody.type(contentType));
	}

	private static boolean form(String contentType) {
		if (contentType == null) {
			return false;
		}
		int end = contentType.indexOf(';');
		String type = (end < 0 ? contentType : contentType.substring(0, end)).trim();
		return type.equalsIgnoreCase("application/x-www-form-urlencoded");
	}

	private String formBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		try {
			InputStream in = body();
			int limit = this.context.maxFormSize();
			int read;
			while ((read = in.read(chunk)) >= 0) {
				if (limit >= 0 && body.size() + read > limit) {
					throw new IllegalStateException("the form is longer than " + limit + " bytes");
				}
				body.write(chunk, 0, read);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("the form could not be read", ex);
		}
		// The pairs are ASCII with escapes; an octet sent raw is read as one character.
		return new String(body.toByteArray(), StandardCharsets.ISO_8859_1);
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		if (this.parameterMap == null) {
			Map<String, List<String>> merged = new LinkedHashMap<>();
			List<Map<String, List<String>>> dispatched = this.dispatchParameters;
			if (dispatched != null) {
				for (int i = dispatched.size() - 1; i >= 0; i--) {
					merge(dispatched.get(i), merged);
				}
			}
			merge(ownParameters(), merged);
			Map<String, String[]> map = new LinkedHashMap<>();
			for (Map.Entry<String, List<String>> parameter : merged.entrySet()) {
				map.put(parameter.getKey(), parameter.getValue().toArray(new String[0]));
			}
			this.parameterMap = Collections.unmodifiableMap(map);
		}
		return this.parameterMap;
	}

	private static void merge(Map<String, List<String>> from, Map<String, List<String>> into) {
		for (Map.Entry<String, List<String>> parameter : from.entrySet()) {
			List<String> values = into.get(parameter.getKey());
			if (values == null) {
				values = new ArrayList<>();
				into.put(parameter.getKey(), values);
			}
			values.addAll(parameter.getValue());
		}
	}

	@Override
	public @Nullable String getParameter(String name) {
		String[] values = getParameterMap().get(name);
		return values == null ? null : values[0];
	}

	@Override
	public Enumeration<String> getParameterNames() {
		return Collections.enumeration(getParameterMap().keySet());
	}

	@Override
	public String @Nullable [] getParameterValues(String name) {
		String[] values = getParameterMap().get(name);
		return values == null ? null : values.clone();
	}

	// ---- attributes

	@Override
	public @Nullable Object getAttribute(String name) {
		return this.attributes.get(name);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		return Collections.enumeration(new ArrayList<>(this.attributes.keySet()));
	}

	@Override
	public void setAttribute(String name, Object o) {
		if (name == null) {
			throw new NullPointerException("name");
		}
		if (o == null) {
			removeAttribute(name);
			return;
		}
		Object old = this.attributes.put(name, o);
		List<ServletRequestAttributeListener> listeners = this.context.requestAttributeListeners;
		for (int i = 0; i < listeners.size(); i++) {
			if (old == null) {
				listeners.get(i).attributeAdded(new ServletRequestAttributeEvent(this.context, this, name, o));
			}
			else {
				listeners.get(i).attributeReplaced(new ServletRequestAttributeEvent(this.context, this, name, old));
			}
		}
	}

	@Override
	public void removeAttribute(String name) {
		Object old = this.attributes.remove(name);
		List<ServletRequestAttributeListener> listeners = this.context.requestAttributeListeners;
		for (int i = 0; old != null && i < listeners.size(); i++) {
			listeners.get(i).attributeRemoved(new ServletRequestAttributeEvent(this.context, this, name, old));
		}
	}

	// ---- the container

	@Override
	public ServletContext getServletContext() {
		return this.context;
	}

	@Override
	public DispatcherType getDispatcherType() {
		return this.dispatcherType;
	}

	@Override
	public @Nullable RequestDispatcher getRequestDispatcher(String path) {
		if (path == null) {
			return null;
		}
		if (path.startsWith("/")) {
			return this.context.getRequestDispatcher(path);
		}
		String from = getServletPath() + (getPathInfo() == null ? "" : getPathInfo());
		return this.context.getRequestDispatcher(from.substring(0, from.lastIndexOf('/') + 1) + path);
	}

	@Override
	public String getRequestId() {
		if (this.id == null) {
			this.id = Long.toString(IDS.incrementAndGet());
		}
		return this.id;
	}

	@Override
	public String getProtocolRequestId() {
		return "";
	}

	@Override
	public ServletConnection getServletConnection() {
		String connection = getRemoteAddr() + ":" + getRemotePort() + "-" + getLocalPort();
		return new ServletConnection() {
			@Override
			public String getConnectionId() {
				return connection;
			}

			@Override
			public String getProtocol() {
				return "http/1.1";
			}

			@Override
			public String getProtocolConnectionId() {
				return "";
			}

			@Override
			public boolean isSecure() {
				return Request.this.context.secure();
			}
		};
	}

	// ---- asynchronous requests

	@Override
	public AsyncContext startAsync() {
		Async async = this.async;
		if (async != null && async.request() != null) {
			// the request and the response a cycle before was started with stay
			return begin(async.request(), async.response(), async.hasOriginalRequestAndResponse(), null, null);
		}
		return begin(this, this.response, true, null, null);
	}

	@Override
	public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
		if (servletRequest == null || servletResponse == null) {
			throw new IllegalArgumentException("an asynchronous cycle is started with a request and a response");
		}
		if (Request.of(servletRequest) != this || Response.of(servletResponse) != this.response) {
			throw new IllegalArgumentException("not this request and its response, nor wrappers of them");
		}
		String uri = null;
		String query = null;
		if (servletRequest instanceof HttpServletRequest http) {
			uri = http.getRequestURI();
			query = http.getQueryString();
		}
		return begin(servletRequest, servletResponse, servletRequest == this && servletResponse == this.response, uri,
				query);
	}

	private AsyncContext begin(ServletRequest request, ServletResponse response, boolean original, @Nullable String uri,
			@Nullable String query) {
		if (!this.asyncSupported) {
			throw new IllegalStateException(
					"a filter or the servlet the request is inside of does not support asynchronous requests");
		}
		if (!this.dispatching) {
			throw new IllegalStateException("startAsync is called inside a dispatch of the container");
		}
		if (this.async != null && this.async.starting()) {
			throw new IllegalStateException("startAsync has been called in this dispatch");
		}
		if (this.response.ended()) {
			throw new IllegalStateException("the response has been completed");
		}
		if (this.async == null) {
			this.async = new Async(this.context, this, this.response);
		}
		this.async.start(request, response, original, uri, query);
		return this.async;
	}

	@Override
	public boolean isAsyncStarted() {
		return this.async != null && this.async.started();
	}

	@Override
	public boolean isAsyncSupported() {
		return this.asyncSupported;
	}

	@Override
	public AsyncContext getAsyncContext() {
		if (this.async == null) {
			throw new IllegalStateException("the request has not been put into asynchronous mode");
		}
		return this.async;
	}

	// ---- what this container does not have: security, protocol upgrades

	// ---- the session

	@Override
	public @Nullable HttpSession getSession(boolean create) {
		if (this.session != null) {
			if (this.session.valid()) {
				return this.session;
			}
			this.session = null;
		}
		if (!this.sessionLooked) {
			this.sessionLooked = true;
			String requested = getRequestedSessionId();
			if (requested != null) {
				Sessions sessions = this.context.sessions();
				SessionData found = sessions.access(requested, true);
				if (found != null) {
					this.session = new Session(sessions, found);
					return this.session;
				}
			}
		}
		if (!create) {
			return null;
		}
		if (this.response.isCommitted()) {
			throw new IllegalStateException("a session cannot be made once the response has been committed");
		}
		Sessions sessions = this.context.sessions();
		Session made = new Session(sessions, sessions.create());
		this.session = made;
		sendCookie(made);
		return made;
	}

	@Override
	public HttpSession getSession() {
		// one is made where there is none
		return Objects.requireNonNull(getSession(true));
	}

	private void sendCookie(Session session) {
		if (this.context.tracks(SessionTrackingMode.COOKIE)) {
			CookieConfig cookie = this.context.cookieConfig();
			this.response.sessionCookie(cookie.cookieName(), cookie.setCookie(session.getId()));
		}
	}

	@Override
	public String changeSessionId() {
		Session current = (Session) getSession(false);
		if (current == null) {
			throw new IllegalStateException("the request has no session");
		}
		this.context.sessions().changeId(current.data);
		sendCookie(current);
		return current.getId();
	}

	@Override
	public @Nullable String getRequestedSessionId() {
		if (!this.requestedRead) {
			this.requestedRead = true;
			if (this.context.tracks(SessionTrackingMode.COOKIE)) {
				List<String> fields = this.exchange.getRequestHeaders().get("Cookie");
				List<String> ids = new ArrayList<>();
				String name = this.context.cookieConfig().cookieName();
				for (int i = 0; fields != null && i < fields.size(); i++) {
					Fields.cookieValues(fields.get(i), name, ids);
				}
				// Of several, the first that names a session (another path's cookie may
				// come first).
				for (int i = 0; i < ids.size() && this.requestedSessionId == null; i++) {
					if (i == ids.size() - 1 || this.context.sessions().valid(ids.get(i))) {
						this.requestedSessionId = ids.get(i);
						this.requestedFromCookie = true;
					}
				}
			}
			if (this.requestedSessionId == null && this.context.tracks(SessionTrackingMode.URL)) {
				this.requestedSessionId = pathSessionId(this.exchange.getRequestURI().getRawPath());
			}
		}
		return this.requestedSessionId;
	}

	/** The id a path carries as its parameter {@code jsessionid} (Servlet 6.1, 7.1.3). */
	private static @Nullable String pathSessionId(String path) {
		int at = path.indexOf(";" + PATH_PARAMETER + "=");
		if (at < 0) {
			return null;
		}
		int start = at + PATH_PARAMETER.length() + 2;
		int end = start;
		while (end < path.length() && path.charAt(end) != ';' && path.charAt(end) != '/') {
			end++;
		}
		return end > start ? path.substring(start, end) : null;
	}

	@Override
	public boolean isRequestedSessionIdValid() {
		String requested = getRequestedSessionId();
		if (requested == null) {
			return false;
		}
		if (this.session != null && this.session.valid() && requested.equals(this.session.getId())) {
			return true;
		}
		return this.context.sessions().valid(requested);
	}

	@Override
	public boolean isRequestedSessionIdFromCookie() {
		return getRequestedSessionId() != null && this.requestedFromCookie;
	}

	@Override
	public boolean isRequestedSessionIdFromURL() {
		return getRequestedSessionId() != null && !this.requestedFromCookie;
	}

	/**
	 * A URL with the session's id where the session is tracked by URLs, the client did
	 * not send it as a cookie and the URL is of this application.
	 */
	String encode(String url) {
		if (!this.context.tracks(SessionTrackingMode.URL) || isRequestedSessionIdFromCookie()
				|| url.contains(";" + PATH_PARAMETER + "=") || !ofThisApplication(url)) {
			return url;
		}
		HttpSession current = getSession(false);
		if (current == null) {
			return url;
		}
		int end = url.length();
		for (int i = 0; i < url.length(); i++) {
			if (url.charAt(i) == '?' || url.charAt(i) == '#') {
				end = i;
				break;
			}
		}
		return url.substring(0, end) + ";" + PATH_PARAMETER + "=" + current.getId() + url.substring(end);
	}

	/** A relative URL, or an absolute one of this server and context. */
	private boolean ofThisApplication(String url) {
		int colon = url.indexOf(':');
		int slash = url.indexOf('/');
		if (url.startsWith("//") || (colon > 0 && (slash < 0 || colon < slash))) {
			String origin = getRequestURL().toString();
			origin = origin.substring(0, origin.indexOf('/', origin.indexOf("://") + 3));
			String path = url.startsWith("//") ? null : url.startsWith(origin) ? url.substring(origin.length()) : null;
			String context = getContextPath();
			return path != null && path.startsWith(context)
					&& (path.length() == context.length() || "/?#".indexOf(path.charAt(context.length())) >= 0);
		}
		return true;
	}

	@Override
	public @Nullable String getAuthType() {
		return null;
	}

	@Override
	public @Nullable String getRemoteUser() {
		return null;
	}

	@Override
	public boolean isUserInRole(String role) {
		return false;
	}

	@Override
	public @Nullable Principal getUserPrincipal() {
		return null;
	}

	@Override
	public boolean authenticate(HttpServletResponse response) throws ServletException {
		throw new ServletException("no authentication mechanism is configured");
	}

	@Override
	public void login(String username, String password) throws ServletException {
		throw new ServletException("no authentication mechanism is configured");
	}

	@Override
	public void logout() {
	}

	@Override
	public Collection<Part> getParts() throws IOException, ServletException {
		return Collections.unmodifiableList(new ArrayList<Part>(parts()));
	}

	@Override
	public @Nullable Part getPart(String name) throws IOException, ServletException {
		List<FormPart> parts = parts();
		for (int i = 0; i < parts.size(); i++) {
			if (parts.get(i).getName().equals(name)) {
				return parts.get(i);
			}
		}
		return null;
	}

	/**
	 * The parts of a multipart body, read by the configuration of the servlet the request
	 * is dispatched to.
	 */
	private List<FormPart> parts() throws IOException, ServletException {
		if (!multipart(getContentType())) {
			throw new ServletException("the request is not multipart/form-data");
		}
		Multipart multipart = this.match == null ? null : this.match.servlet.multipart;
		if (multipart == null) {
			throw new IllegalStateException("the servlet has no multipart configuration");
		}
		return multipart.parts(this, this.streamTaken || this.reader != null);
	}

	/** Deletes the files the container made for the parts: the request is complete. */
	void complete() {
		List<FormPart> parts = this.parts;
		for (int i = 0; parts != null && i < parts.size(); i++) {
			parts.get(i).discard();
		}
	}

	@Override
	public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
		throw new UnsupportedOperationException("protocol upgrades are not supported");
	}

}
