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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

/**
 * A request over an exchange. One thread reads it; a dispatch changes the path it answers
 * and puts the query of its target in front of the parameters, and gives both back when
 * it returns.
 */
final class Request implements HttpServletRequest {

	/** The longest form body read into parameters. */
	private static final int MAX_FORM_SIZE = 2 * 1024 * 1024;

	private static final AtomicLong IDS = new AtomicLong();

	private final WebContext context;

	private final HttpExchange exchange;

	final Map<String, Object> attributes = new LinkedHashMap<>();

	private String requestUri;

	private String queryString;

	private Match match;

	private DispatcherType dispatcherType = DispatcherType.REQUEST;

	private String characterEncoding;

	private RequestBody input;

	private boolean streamTaken;

	private BufferedReader reader;

	private Map<String, List<String>> ownParameters;

	private List<Map<String, List<String>>> dispatchParameters;

	private Map<String, String[]> parameterMap;

	private Cookie[] cookies;

	private boolean cookiesRead;

	private String id;

	/**
	 * A request.
	 * @param requestUri the path as the request line has it
	 * @param match the servlet the path is mapped to, or null where none is: only a
	 * listener sees such a request
	 */
	Request(WebContext context, HttpExchange exchange, String requestUri, Match match) {
		this.context = context;
		this.exchange = exchange;
		this.requestUri = requestUri;
		this.queryString = exchange.getRequestURI().getRawQuery();
		this.match = match;
	}

	// ---- dispatch

	/** What a dispatch changes of a request, kept to be given back. */
	static final class Target {

		private final String requestUri;

		private final String queryString;

		private final Match match;

		private final DispatcherType dispatcherType;

		Target(String requestUri, String queryString, Match match, DispatcherType dispatcherType) {
			this.requestUri = requestUri;
			this.queryString = queryString;
			this.match = match;
			this.dispatcherType = dispatcherType;
		}

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
	Target dispatch(Match match, String path, String query, DispatcherType type) {
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
		this.requestUri = before.requestUri;
		this.queryString = before.queryString;
		this.match = before.match;
		this.dispatcherType = before.dispatcherType;
		if (hadQuery) {
			this.dispatchParameters.remove(this.dispatchParameters.size() - 1);
			this.parameterMap = null;
		}
	}

	/** The request of this container a servlet's request is, through its wrappers. */
	static Request of(ServletRequest request) {
		ServletRequest current = request;
		while (current instanceof jakarta.servlet.ServletRequestWrapper) {
			current = ((jakarta.servlet.ServletRequestWrapper) current).getRequest();
		}
		if (current instanceof Request) {
			return (Request) current;
		}
		throw new IllegalArgumentException("not a request of this container, nor a wrapper of one");
	}

	// ---- the request line

	@Override
	public String getMethod() {
		return this.exchange.getRequestMethod();
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
		return this.match == null ? "" : this.match.servletPath;
	}

	@Override
	public String getPathInfo() {
		return this.match == null ? null : this.match.pathInfo;
	}

	@Override
	public String getPathTranslated() {
		return null;
	}

	@Override
	public String getQueryString() {
		return this.queryString;
	}

	@Override
	public HttpServletMapping getHttpServletMapping() {
		return this.match;
	}

	@Override
	public String getProtocol() {
		return this.exchange.getProtocol();
	}

	@Override
	public String getScheme() {
		return "http";
	}

	@Override
	public boolean isSecure() {
		return false;
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
		if (colon < 0) {
			return 80;
		}
		try {
			return Integer.parseInt(host.substring(colon + 1));
		}
		catch (NumberFormatException ex) {
			return 80;
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
	public Cookie[] getCookies() {
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
		return Fields.locales(this.exchange.getRequestHeaders().get("Accept-Language")).get(0);
	}

	@Override
	public Enumeration<Locale> getLocales() {
		return Collections.enumeration(Fields.locales(this.exchange.getRequestHeaders().get("Accept-Language")));
	}

	// ---- the body

	@Override
	public String getCharacterEncoding() {
		if (this.characterEncoding != null) {
			return this.characterEncoding;
		}
		String charset = Fields.parameter(getContentType(), "charset");
		return charset != null ? charset : this.context.getRequestCharacterEncoding();
	}

	@Override
	public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
		if (this.reader != null || this.ownParameters != null) {
			return;
		}
		if (encoding != null && charset(encoding) == null) {
			throw new UnsupportedEncodingException(encoding);
		}
		this.characterEncoding = encoding;
	}

	private static Charset charset(String name) {
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

	private RequestBody body() {
		if (this.input == null) {
			this.input = new RequestBody(this.exchange.getRequestBody());
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
				String encoding = getCharacterEncoding();
				Charset charset = encoding == null ? null : charset(encoding);
				UrlCodec.parameters(formBody(), charset == null ? StandardCharsets.UTF_8 : charset, parameters);
			}
			this.ownParameters = parameters;
		}
		return this.ownParameters;
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
			int read;
			while ((read = in.read(chunk)) >= 0) {
				if (body.size() + read > MAX_FORM_SIZE) {
					throw new IllegalStateException("the form is longer than " + MAX_FORM_SIZE + " bytes");
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
			for (int i = dispatched == null ? -1 : dispatched.size() - 1; i >= 0; i--) {
				merge(dispatched.get(i), merged);
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
	public String getParameter(String name) {
		String[] values = getParameterMap().get(name);
		return values == null ? null : values[0];
	}

	@Override
	public Enumeration<String> getParameterNames() {
		return Collections.enumeration(getParameterMap().keySet());
	}

	@Override
	public String[] getParameterValues(String name) {
		String[] values = getParameterMap().get(name);
		return values == null ? null : values.clone();
	}

	// ---- attributes

	@Override
	public Object getAttribute(String name) {
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
	public RequestDispatcher getRequestDispatcher(String path) {
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
				return false;
			}
		};
	}

	// ---- what this container does not have: asynchronous requests, sessions, security,
	// multipart bodies, protocol upgrades

	@Override
	public AsyncContext startAsync() {
		throw new IllegalStateException("asynchronous requests are not supported");
	}

	@Override
	public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
		throw new IllegalStateException("asynchronous requests are not supported");
	}

	@Override
	public boolean isAsyncStarted() {
		return false;
	}

	@Override
	public boolean isAsyncSupported() {
		return false;
	}

	@Override
	public AsyncContext getAsyncContext() {
		throw new IllegalStateException("the request is not asynchronous");
	}

	@Override
	public HttpSession getSession(boolean create) {
		if (create) {
			throw new UnsupportedOperationException("sessions are not supported");
		}
		return null;
	}

	@Override
	public HttpSession getSession() {
		return getSession(true);
	}

	@Override
	public String changeSessionId() {
		throw new IllegalStateException("the request has no session");
	}

	@Override
	public String getRequestedSessionId() {
		return null;
	}

	@Override
	public boolean isRequestedSessionIdValid() {
		return false;
	}

	@Override
	public boolean isRequestedSessionIdFromCookie() {
		return false;
	}

	@Override
	public boolean isRequestedSessionIdFromURL() {
		return false;
	}

	@Override
	public String getAuthType() {
		return null;
	}

	@Override
	public String getRemoteUser() {
		return null;
	}

	@Override
	public boolean isUserInRole(String role) {
		return false;
	}

	@Override
	public Principal getUserPrincipal() {
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
	public Collection<Part> getParts() throws ServletException {
		String type = getContentType();
		if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
			throw new ServletException("the request is not multipart/form-data");
		}
		throw new IllegalStateException("multipart requests are not supported");
	}

	@Override
	public Part getPart(String name) throws ServletException {
		getParts();
		return null;
	}

	@Override
	public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
		throw new UnsupportedOperationException("protocol upgrades are not supported");
	}

}
