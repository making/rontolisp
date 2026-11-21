package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A response over an exchange. The status and the fields are kept here until the response
 * is committed, which hands them to the exchange once; from then on they are fixed and
 * only the body goes on.
 */
final class Response implements HttpServletResponse {

	private static final String DEFAULT_ENCODING = "ISO-8859-1";

	private final WebContext context;

	private final HttpExchange exchange;

	private final ResponseBody body = new ResponseBody(this);

	/**
	 * A field by its name in lower case: the name as it was first written, its values.
	 */
	private final Map<String, List<String>> fields = new LinkedHashMap<>();

	/**
	 * How many includes the request is inside of: an included servlet changes no field.
	 */
	int including;

	private int status = SC_OK;

	private String contentType;

	private String characterEncoding;

	private long contentLength = -1;

	private Locale locale;

	private PrintWriter writer;

	private ResponseWriter encoder;

	private boolean streamTaken;

	private boolean committed;

	Response(WebContext context, HttpExchange exchange) {
		this.context = context;
		this.exchange = exchange;
		this.characterEncoding = context.getResponseCharacterEncoding();
	}

	/** The response of this container a servlet's response is, through its wrappers. */
	static Response of(ServletResponse response) {
		ServletResponse current = response;
		while (current instanceof ServletResponseWrapper) {
			current = ((ServletResponseWrapper) current).getResponse();
		}
		if (current instanceof Response) {
			return (Response) current;
		}
		throw new IllegalArgumentException("not a response of this container, nor a wrapper of one");
	}

	// ---- the head goes out

	/** Whether the status and the fields can no longer be changed. */
	private boolean fixed() {
		return this.committed || this.including > 0;
	}

	long declaredLength() {
		return this.contentLength;
	}

	/**
	 * Hands the status and the fields to the exchange.
	 * @param whole the length of the whole body where the response ends uncommitted, else
	 * -1
	 * @return where the body is written, or null for a response with no body
	 */
	OutputStream commit(long whole) throws IOException {
		this.committed = true;
		Headers out = this.exchange.getResponseHeaders();
		for (List<String> field : this.fields.values()) {
			for (int i = 1; i < field.size(); i++) {
				out.add(field.get(0), field.get(i));
			}
		}
		String type = getContentType();
		if (type != null) {
			out.set("Content-Type", type);
		}
		boolean head = "HEAD".equals(this.exchange.getRequestMethod());
		long length = this.contentLength >= 0 && (whole < 0 || head) ? this.contentLength : whole;
		if (head || this.status < 200 || this.status == SC_NO_CONTENT || this.status == SC_NOT_MODIFIED) {
			// The exchange writes no length for these; a field of the response is kept.
			if (head ? length >= 0 : this.status == SC_NOT_MODIFIED && this.contentLength >= 0) {
				out.set("Content-Length", Long.toString(length));
			}
			this.exchange.sendResponseHeaders(this.status, -1);
			return null;
		}
		if (length == 0) {
			this.exchange.sendResponseHeaders(this.status, -1);
			return null;
		}
		// A length the exchange is told must be met; with none it sends chunks.
		this.exchange.sendResponseHeaders(this.status, length < 0 ? 0 : length);
		return this.exchange.getResponseBody();
	}

	/** Ends the response. What is written afterwards is dropped. */
	void complete() {
		try {
			this.body.finish();
		}
		catch (IOException ex) {
			// The client is gone, or less was written than the response declared.
		}
	}

	/** Answers an error for the container, where the response is not committed. */
	void fail(int sc) {
		if (!this.committed) {
			this.including = 0;
			error(sc, null);
		}
	}

	private void error(int sc, String message) {
		this.body.clear();
		if (this.encoder != null) {
			this.encoder.clear();
		}
		this.status = sc;
		this.contentLength = -1;
		this.contentType = "text/html";
		this.characterEncoding = "UTF-8";
		String reason = reason(sc);
		String title = reason.isEmpty() ? Integer.toString(sc) : sc + " " + reason;
		StringBuilder page = new StringBuilder();
		page.append("<!doctype html><html><head><title>").append(title).append("</title></head><body><h1>");
		page.append(title).append("</h1>");
		if (message != null) {
			page.append("<p>");
			for (int i = 0; i < message.length(); i++) {
				char c = message.charAt(i);
				switch (c) {
					case '<':
						page.append("&lt;");
						break;
					case '>':
						page.append("&gt;");
						break;
					case '&':
						page.append("&amp;");
						break;
					case '"':
						page.append("&quot;");
						break;
					default:
						page.append(c);
				}
			}
			page.append("</p>");
		}
		page.append("</body></html>");
		byte[] octets = page.toString().getBytes(StandardCharsets.UTF_8);
		try {
			this.body.write(octets, 0, octets.length);
		}
		catch (IOException ex) {
			// The page fits the buffer or the client is gone.
		}
		complete();
	}

	private static String reason(int sc) {
		switch (sc) {
			case 400:
				return "Bad Request";
			case 401:
				return "Unauthorized";
			case 403:
				return "Forbidden";
			case 404:
				return "Not Found";
			case 405:
				return "Method Not Allowed";
			case 406:
				return "Not Acceptable";
			case 408:
				return "Request Timeout";
			case 409:
				return "Conflict";
			case 410:
				return "Gone";
			case 411:
				return "Length Required";
			case 412:
				return "Precondition Failed";
			case 413:
				return "Content Too Large";
			case 414:
				return "URI Too Long";
			case 415:
				return "Unsupported Media Type";
			case 422:
				return "Unprocessable Content";
			case 429:
				return "Too Many Requests";
			case 500:
				return "Internal Server Error";
			case 501:
				return "Not Implemented";
			case 502:
				return "Bad Gateway";
			case 503:
				return "Service Unavailable";
			case 504:
				return "Gateway Timeout";
			default:
				return "";
		}
	}

	// ---- the status

	@Override
	public void setStatus(int sc) {
		if (!fixed()) {
			this.status = sc;
		}
	}

	@Override
	public int getStatus() {
		return this.status;
	}

	@Override
	public void sendError(int sc) throws IOException {
		sendError(sc, null);
	}

	@Override
	public void sendError(int sc, String msg) throws IOException {
		if (this.including > 0) {
			return;
		}
		if (this.committed) {
			throw new IllegalStateException("the response has been committed");
		}
		error(sc, msg);
	}

	@Override
	public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
		if (this.including > 0) {
			return;
		}
		if (this.committed) {
			throw new IllegalStateException("the response has been committed");
		}
		if (location == null) {
			throw new IllegalArgumentException("a redirect needs a location");
		}
		if (clearBuffer) {
			resetBuffer();
			this.contentLength = -1;
		}
		this.status = sc;
		setHeader("Location", location);
		complete();
	}

	// ---- the fields

	@Override
	public void setHeader(String name, String value) {
		if (name == null || fixed() || own(name, value)) {
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		if (value == null) {
			this.fields.remove(key);
			return;
		}
		List<String> field = new ArrayList<>(2);
		field.add(name);
		field.add(value);
		this.fields.put(key, field);
	}

	@Override
	public void addHeader(String name, String value) {
		if (name == null || value == null || fixed() || own(name, value)) {
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		List<String> field = this.fields.get(key);
		if (field == null) {
			field = new ArrayList<>(2);
			field.add(name);
			this.fields.put(key, field);
		}
		field.add(value);
	}

	/** The two fields the response keeps apart, set through their own members. */
	private boolean own(String name, String value) {
		if (name.equalsIgnoreCase("Content-Type")) {
			setContentType(value);
			return true;
		}
		if (name.equalsIgnoreCase("Content-Length")) {
			try {
				setContentLengthLong(value == null ? -1 : Long.parseLong(value.trim()));
			}
			catch (NumberFormatException ex) {
				// Not a length: the field is not set.
			}
			return true;
		}
		return false;
	}

	@Override
	public void setIntHeader(String name, int value) {
		setHeader(name, Integer.toString(value));
	}

	@Override
	public void addIntHeader(String name, int value) {
		addHeader(name, Integer.toString(value));
	}

	@Override
	public void setDateHeader(String name, long date) {
		setHeader(name, HttpDates.format(date));
	}

	@Override
	public void addDateHeader(String name, long date) {
		addHeader(name, HttpDates.format(date));
	}

	@Override
	public boolean containsHeader(String name) {
		return getHeader(name) != null;
	}

	@Override
	public String getHeader(String name) {
		if (name == null) {
			return null;
		}
		if (name.equalsIgnoreCase("Content-Type")) {
			return getContentType();
		}
		if (name.equalsIgnoreCase("Content-Length")) {
			return this.contentLength < 0 ? null : Long.toString(this.contentLength);
		}
		List<String> field = this.fields.get(name.toLowerCase(Locale.ROOT));
		return field == null ? null : field.get(1);
	}

	@Override
	public Collection<String> getHeaders(String name) {
		String own = name == null ? null : getHeader(name);
		if (own == null) {
			return Collections.emptyList();
		}
		List<String> field = this.fields.get(name.toLowerCase(Locale.ROOT));
		return field == null ? Collections.singletonList(own) : new ArrayList<>(field.subList(1, field.size()));
	}

	@Override
	public Collection<String> getHeaderNames() {
		List<String> names = new ArrayList<>();
		if (this.contentType != null) {
			names.add("Content-Type");
		}
		if (this.contentLength >= 0) {
			names.add("Content-Length");
		}
		for (List<String> field : this.fields.values()) {
			names.add(field.get(0));
		}
		return names;
	}

	@Override
	public void addCookie(Cookie cookie) {
		addHeader("Set-Cookie", Fields.setCookie(cookie));
	}

	@Override
	public String encodeURL(String url) {
		return url;
	}

	@Override
	public String encodeRedirectURL(String url) {
		return url;
	}

	// ---- the content

	@Override
	public String getCharacterEncoding() {
		return this.characterEncoding == null ? DEFAULT_ENCODING : this.characterEncoding;
	}

	@Override
	public void setCharacterEncoding(String encoding) {
		if (!fixed() && this.writer == null) {
			this.characterEncoding = encoding == null ? this.context.getResponseCharacterEncoding() : encoding;
		}
	}

	@Override
	public String getContentType() {
		if (this.contentType == null || this.characterEncoding == null) {
			return this.contentType;
		}
		return this.contentType + ";charset=" + this.characterEncoding;
	}

	@Override
	public void setContentType(String type) {
		if (fixed()) {
			return;
		}
		if (type == null) {
			this.contentType = null;
			return;
		}
		String charset = Fields.parameter(type, "charset");
		this.contentType = charset == null ? type : Fields.withoutParameter(type, "charset");
		if (charset != null && this.writer == null) {
			this.characterEncoding = charset;
		}
	}

	@Override
	public void setContentLength(int len) {
		setContentLengthLong(len);
	}

	@Override
	public void setContentLengthLong(long len) {
		if (!fixed()) {
			this.contentLength = len < 0 ? -1 : len;
		}
	}

	@Override
	public void setLocale(Locale loc) {
		if (!fixed()) {
			this.locale = loc;
			setHeader("Content-Language", loc == null ? null : loc.toLanguageTag());
		}
	}

	@Override
	public Locale getLocale() {
		return this.locale == null ? Locale.getDefault() : this.locale;
	}

	// ---- the body

	@Override
	public ServletOutputStream getOutputStream() {
		if (this.writer != null) {
			throw new IllegalStateException("getWriter() has been called for this response");
		}
		this.streamTaken = true;
		return this.body;
	}

	@Override
	public PrintWriter getWriter() throws IOException {
		if (this.writer == null) {
			if (this.streamTaken) {
				throw new IllegalStateException("getOutputStream() has been called for this response");
			}
			String encoding = getCharacterEncoding();
			Charset charset;
			try {
				charset = Charset.forName(encoding);
			}
			catch (IllegalArgumentException ex) {
				throw new UnsupportedEncodingException(encoding);
			}
			// The writer fixes the encoding, the default one included.
			this.characterEncoding = encoding;
			this.encoder = new ResponseWriter(this.body, charset);
			this.writer = new PrintWriter(this.encoder);
		}
		return this.writer;
	}

	@Override
	public int getBufferSize() {
		return this.body.capacity();
	}

	@Override
	public void setBufferSize(int size) {
		if (this.committed || this.body.written() > 0) {
			throw new IllegalStateException("content has been written");
		}
		this.body.capacity(size);
	}

	@Override
	public void flushBuffer() throws IOException {
		this.body.flush();
	}

	@Override
	public void resetBuffer() {
		if (this.committed) {
			throw new IllegalStateException("the response has been committed");
		}
		this.body.clear();
		if (this.encoder != null) {
			this.encoder.clear();
		}
	}

	@Override
	public boolean isCommitted() {
		return this.committed;
	}

	@Override
	public void reset() {
		if (this.including > 0) {
			return;
		}
		resetBuffer();
		this.fields.clear();
		this.status = SC_OK;
		this.contentType = null;
		this.characterEncoding = this.context.getResponseCharacterEncoding();
		this.contentLength = -1;
		this.locale = null;
		this.writer = null;
		this.encoder = null;
		this.streamTaken = false;
	}

}
