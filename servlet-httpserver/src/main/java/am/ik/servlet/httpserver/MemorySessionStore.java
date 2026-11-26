package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The sessions in the server's memory: the store keeps the object itself, so the requests
 * of a session share it. They last as long as the process, or the instance, they are in.
 */
final class MemorySessionStore implements SessionStore {

	private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

	@Override
	public @Nullable SessionData load(String id) {
		return this.sessions.get(id);
	}

	@Override
	public void save(SessionData session) {
		this.sessions.put(session.getId(), session);
	}

	@Override
	public void delete(String id) {
		this.sessions.remove(id);
	}

	@Override
	public Collection<SessionData> expired(long now) {
		List<SessionData> expired = new ArrayList<>();
		for (SessionData session : this.sessions.values()) {
			if (session.isExpired(now)) {
				expired.add(session);
			}
		}
		return expired;
	}

	/** Every session, which ends with the server. */
	Collection<SessionData> all() {
		return new ArrayList<>(this.sessions.values());
	}

}
