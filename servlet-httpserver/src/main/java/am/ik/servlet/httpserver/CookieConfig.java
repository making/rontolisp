package am.ik.servlet.httpserver;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.SessionCookieConfig;
import org.jspecify.annotations.Nullable;

/**
 * The cookie a session is tracked by: its name and its attributes, set until the context
 * is initialized. An attribute's setter and {@link #setAttribute} write the same table,
 * whose names are matched in any case.
 */
final class CookieConfig implements SessionCookieConfig {

	static final String DEFAULT_NAME = "JSESSIONID";

	private static final String DOMAIN = "Domain";

	private static final String PATH = "Path";

	private static final String MAX_AGE = "Max-Age";

	private static final String HTTP_ONLY = "HttpOnly";

	private static final String SECURE = "Secure";

	private final WebContext context;

	private @Nullable String name;

	private final Map<String, String> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	CookieConfig(WebContext context) {
		this.context = context;
		// Not readable by a script of the page unless the application says so.
		this.attributes.put(HTTP_ONLY, "");
	}

	/** The name the cookie is sent and read by. */
	String cookieName() {
		return this.name == null ? DEFAULT_NAME : this.name;
	}

	/**
	 * The value of a {@code Set-Cookie} field for a session (RFC 6265, 4.1.1): the pair,
	 * then the attributes; the path is the context's unless one was set.
	 * @param id the session's id
	 */
	String setCookie(String id) {
		Map<String, String> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		attributes.putAll(this.attributes);
		if (!attributes.containsKey(PATH)) {
			String contextPath = this.context.getContextPath();
			attributes.put(PATH, contextPath.isEmpty() ? "/" : contextPath);
		}
		if (this.context.secure() && !attributes.containsKey(SECURE)) {
			// a session begun over HTTPS is not sent over HTTP
			attributes.put(SECURE, "");
		}
		StringBuilder field = new StringBuilder(cookieName()).append('=').append(id);
		for (Map.Entry<String, String> attribute : attributes.entrySet()) {
			field.append("; ").append(attribute.getKey());
			if (!attribute.getValue().isEmpty()) {
				field.append('=').append(attribute.getValue());
			}
		}
		return field.toString();
	}

	private void put(String name, @Nullable String value) {
		this.context.open();
		if (value == null) {
			this.attributes.remove(name);
		}
		else {
			this.attributes.put(name, value);
		}
	}

	private boolean flag(String name) {
		String value = this.attributes.get(name);
		return value != null && !value.equalsIgnoreCase("false");
	}

	@Override
	public void setName(String name) {
		this.context.open();
		if (name == null || !Fields.token(name)) {
			throw new IllegalArgumentException("not the name of a cookie: " + name);
		}
		this.name = name;
	}

	@Override
	public @Nullable String getName() {
		return this.name;
	}

	@Override
	public void setDomain(@Nullable String domain) {
		put(DOMAIN, domain);
	}

	@Override
	public @Nullable String getDomain() {
		return this.attributes.get(DOMAIN);
	}

	@Override
	public void setPath(@Nullable String path) {
		put(PATH, path);
	}

	@Override
	public @Nullable String getPath() {
		return this.attributes.get(PATH);
	}

	@Override
	@SuppressWarnings("removal")
	public void setComment(@Nullable String comment) {
		this.context.open();
	}

	@Override
	@SuppressWarnings("removal")
	public @Nullable String getComment() {
		return null;
	}

	@Override
	public void setHttpOnly(boolean httpOnly) {
		put(HTTP_ONLY, httpOnly ? "" : null);
	}

	@Override
	public boolean isHttpOnly() {
		return flag(HTTP_ONLY);
	}

	@Override
	public void setSecure(boolean secure) {
		put(SECURE, secure ? "" : null);
	}

	@Override
	public boolean isSecure() {
		return flag(SECURE);
	}

	@Override
	public void setMaxAge(int maxAge) {
		put(MAX_AGE, maxAge < 0 ? null : Integer.toString(maxAge));
	}

	@Override
	public int getMaxAge() {
		String maxAge = this.attributes.get(MAX_AGE);
		return maxAge == null ? -1 : Integer.parseInt(maxAge);
	}

	@Override
	public void setAttribute(String name, @Nullable String value) {
		if (name == null || !Fields.token(name)) {
			throw new IllegalArgumentException("not the name of a cookie's attribute: " + name);
		}
		if (MAX_AGE.equalsIgnoreCase(name) && value != null) {
			setMaxAge(Integer.parseInt(value));
		}
		else {
			put(name, value);
		}
	}

	@Override
	public @Nullable String getAttribute(String name) {
		return this.attributes.get(name);
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		copy.putAll(this.attributes);
		return Collections.unmodifiableMap(copy);
	}

}
