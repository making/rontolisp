package am.ik.servlet.httpserver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import am.ik.servlet.httpserver.ServletHttpServer.CookieOptions;
import am.ik.servlet.httpserver.ServletHttpServer.SessionOptions;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;

/**
 * How a builder was told to keep and track sessions, fixed once its lambda returned: each
 * a setting of the context made when a server starts, in the order given, so what an
 * option sets is reached only by a program that gives it.
 */
final class SessionSpec implements SessionOptions, CookieOptions {

	private final List<Consumer<WebContext>> settings = new ArrayList<>();

	private boolean given;

	private SessionSpec() {
	}

	static SessionSpec of(Consumer<SessionOptions> options) {
		SessionSpec spec = new SessionSpec();
		options.accept(spec);
		spec.given = true;
		return spec;
	}

	private SessionSpec set(Consumer<WebContext> setting) {
		Registrations.open(this.given);
		this.settings.add(setting);
		return this;
	}

	private SessionSpec cookieSet(Consumer<SessionCookieConfig> setting) {
		return set(context -> setting.accept(context.getSessionCookieConfig()));
	}

	@Override
	public SessionOptions store(SessionStore store) {
		Objects.requireNonNull(store, "store");
		return set(context -> context.setSessionStore(store));
	}

	@Override
	public SessionOptions timeout(Duration timeout) {
		if (timeout.isNegative() || timeout.toSeconds() % 60 != 0 || timeout.getNano() != 0) {
			throw new IllegalArgumentException("a session's timeout is whole minutes: " + timeout);
		}
		int minutes = (int) Math.min(timeout.toMinutes(), Integer.MAX_VALUE);
		return set(context -> context.setSessionTimeout(minutes));
	}

	@Override
	public SessionOptions trackingModes(SessionTrackingMode... trackingModes) {
		EnumSet<SessionTrackingMode> modes = EnumSet.noneOf(SessionTrackingMode.class);
		for (SessionTrackingMode mode : trackingModes) {
			modes.add(Objects.requireNonNull(mode, "trackingModes"));
		}
		WebContext.trackable(modes);
		return set(context -> context.setSessionTrackingModes(modes));
	}

	@Override
	public SessionOptions cookie(Consumer<CookieOptions> options) {
		Registrations.open(this.given);
		// the cookie's options are this spec's until the sessions' lambda returns
		options.accept(this);
		return this;
	}

	@Override
	public CookieOptions name(String name) {
		if (!Fields.token(name)) {
			throw new IllegalArgumentException("not the name of a cookie: " + name);
		}
		return cookieSet(config -> config.setName(name));
	}

	@Override
	public CookieOptions domain(String domain) {
		Objects.requireNonNull(domain, "domain");
		return cookieSet(config -> config.setDomain(domain));
	}

	@Override
	public CookieOptions path(String path) {
		Objects.requireNonNull(path, "path");
		return cookieSet(config -> config.setPath(path));
	}

	@Override
	public CookieOptions httpOnly(boolean httpOnly) {
		return cookieSet(config -> config.setHttpOnly(httpOnly));
	}

	@Override
	public CookieOptions secure(boolean secure) {
		return cookieSet(config -> config.setSecure(secure));
	}

	@Override
	public CookieOptions maxAge(Duration maxAge) {
		if (maxAge.isNegative() || maxAge.getNano() != 0) {
			throw new IllegalArgumentException("a cookie's maximum age is whole seconds: " + maxAge);
		}
		int seconds = (int) Math.min(maxAge.toSeconds(), Integer.MAX_VALUE);
		return cookieSet(config -> config.setMaxAge(seconds));
	}

	@Override
	public CookieOptions attribute(String name, String value) {
		if (!Fields.token(name)) {
			throw new IllegalArgumentException("not the name of a cookie's attribute: " + name);
		}
		Objects.requireNonNull(value, "value");
		return cookieSet(config -> config.setAttribute(name, value));
	}

	/** Sets the sessions of a context that is starting. */
	void register(WebContext context) {
		for (Consumer<WebContext> setting : this.settings) {
			setting.accept(context);
		}
	}

}
