package am.ik.servlet.httpserver;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.jspecify.annotations.Nullable;

/**
 * The sessions of a context: made, found by the id a request names, expired and
 * invalidated, kept in a {@link SessionStore}. Made by the first request that asks for a
 * session, so an application that never does holds none of it.
 *
 * <p>
 * Nothing runs on a clock: a session found expired is invalidated where it is found, and
 * the store is asked for the expired ones by a request at most once a minute.
 */
final class Sessions {

	/** The least time between two sweeps of the store. */
	private static final long SWEEP_MILLIS = 60_000;

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private final WebContext context;

	private final SessionStore store;

	private final SecureRandom random = new SecureRandom();

	private volatile long nextSweep;

	Sessions(WebContext context, SessionStore store) {
		this.context = context;
		this.store = store;
	}

	static long now() {
		return System.currentTimeMillis();
	}

	/**
	 * The valid session of an id, marked as received by a request.
	 * @param named whether a request named the id, so that the client knows the session
	 * @return the session, or null where there is none or it expired
	 */
	@Nullable SessionData access(String id, boolean named) {
		long now = now();
		sweep(now);
		SessionData data = this.store.load(id);
		if (data == null || data.state != 0) {
			return null;
		}
		if (data.isExpired(now)) {
			invalidate(data);
			return null;
		}
		boolean fresh = data.isFresh();
		data.accessed(now, named);
		if (data.isFresh() != fresh) {
			this.store.save(data);
		}
		else {
			this.store.saveAccess(data);
		}
		return data;
	}

	/** Whether there is a valid session of an id, with no change to it. */
	boolean valid(String id) {
		SessionData data = this.store.load(id);
		return data != null && data.state == 0 && !data.isExpired(now());
	}

	/** Makes a session and tells the listeners. */
	SessionData create() {
		long now = now();
		sweep(now);
		SessionData data = new SessionData(newId(), now, now, now, this.context.getSessionTimeout() * 60, true);
		this.store.save(data);
		List<HttpSessionListener> listeners = this.context.sessionListeners;
		if (!listeners.isEmpty()) {
			HttpSessionEvent event = new HttpSessionEvent(new Session(this, data));
			for (int i = 0; i < listeners.size(); i++) {
				listeners.get(i).sessionCreated(event);
			}
		}
		return data;
	}

	/** Gives a session a new id and tells the listeners. */
	void changeId(SessionData data) {
		String old = data.getId();
		data.setId(newId());
		this.store.save(data);
		this.store.delete(old);
		List<HttpSessionIdListener> listeners = this.context.sessionIdListeners;
		if (!listeners.isEmpty()) {
			HttpSessionEvent event = new HttpSessionEvent(new Session(this, data));
			for (int i = 0; i < listeners.size(); i++) {
				listeners.get(i).sessionIdChanged(event, old);
			}
		}
	}

	/**
	 * Invalidates a session: the listeners are told while its attributes are still there,
	 * in the reverse of their order; then it is taken from the store and its attributes
	 * are unbound.
	 */
	void invalidate(SessionData data) {
		synchronized (data) {
			if (data.state != 0) {
				throw new IllegalStateException("the session has been invalidated");
			}
			data.state = SessionData.EXPIRING;
		}
		Session session = new Session(this, data);
		List<HttpSessionListener> listeners = this.context.sessionListeners;
		if (!listeners.isEmpty()) {
			HttpSessionEvent event = new HttpSessionEvent(session);
			for (int i = listeners.size() - 1; i >= 0; i--) {
				listeners.get(i).sessionDestroyed(event);
			}
		}
		this.store.delete(data.getId());
		data.state = SessionData.INVALID;
		for (String name : new ArrayList<>(data.getAttributes().keySet())) {
			session.unbind(name);
		}
	}

	/**
	 * The server stops: the sessions in its memory end with it and are invalidated; the
	 * ones another store keeps outlive it.
	 */
	void stop() {
		if (this.store instanceof MemorySessionStore memory) {
			for (SessionData data : memory.all()) {
				try {
					invalidate(data);
				}
				catch (RuntimeException ex) {
					this.context.log("a session failed to be invalidated", ex);
				}
			}
		}
	}

	void save(SessionData data) {
		if (data.state == 0) {
			this.store.save(data);
		}
	}

	@Nullable SessionData load(String id) {
		return this.store.load(id);
	}

	WebContext context() {
		return this.context;
	}

	private void sweep(long now) {
		if (now < this.nextSweep) {
			return;
		}
		this.nextSweep = now + SWEEP_MILLIS;
		for (SessionData data : this.store.expired(now)) {
			try {
				invalidate(data);
			}
			catch (IllegalStateException ex) {
				// Invalidated meanwhile.
			}
		}
	}

	/** 128 bits of the random source, in hexadecimal, that no session has. */
	private String newId() {
		byte[] octets = new byte[16];
		while (true) {
			this.random.nextBytes(octets);
			char[] id = new char[octets.length * 2];
			for (int i = 0; i < octets.length; i++) {
				id[2 * i] = HEX[(octets[i] >> 4) & 0xf];
				id[2 * i + 1] = HEX[octets[i] & 0xf];
			}
			String made = new String(id);
			if (this.store.load(made) == null) {
				return made;
			}
		}
	}

}
