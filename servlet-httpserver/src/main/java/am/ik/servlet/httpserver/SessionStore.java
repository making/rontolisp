package am.ik.servlet.httpserver;

import java.util.Collection;

import org.jspecify.annotations.Nullable;

/**
 * Where the sessions of an application are kept. The container asks the store for a
 * session by the id a request names, hands it back after every change -- a request
 * received, an attribute set or removed, the interval or the id changed -- and deletes it
 * when it is invalidated. A server keeps the sessions in the store it is given
 * ({@link ServletHttpServer.SessionOptions#store}), else in the first one its class
 * loader's service providers of this interface name ({@code META-INF/services}), else in
 * its memory.
 *
 * <p>
 * A store may be asked by several requests at once.
 */
public interface SessionStore {

	/**
	 * The session of an id.
	 * @param id the id a request named
	 * @return the session, or null where the store has none of the id
	 */
	@Nullable SessionData load(String id);

	/**
	 * Keeps a session, made or changed, under its id.
	 * @param session the session
	 * @throws IllegalArgumentException where an attribute is a value the store cannot
	 * keep: {@code setAttribute} throws it, and the session is as it was
	 */
	void save(SessionData session);

	/**
	 * Keeps a session whose one change is a request received: its accessed time is the
	 * request's, its last accessed time the accessed time the store answered. The default
	 * keeps it as {@link #save} does. A store whose writes cost may leave it as kept
	 * while the time moved little; the session then expires that much earlier, and the
	 * next request reads that much older a last accessed time.
	 * @param session the session
	 */
	default void saveAccess(SessionData session) {
		save(session);
	}

	/**
	 * Forgets the session of an id.
	 * @param id the session's id
	 */
	void delete(String id);

	/**
	 * The sessions whose interval passed ({@link SessionData#isExpired(long)}), which the
	 * container then invalidates; asked now and then, never by every request.
	 * @param now the time in milliseconds since the epoch
	 * @return the sessions that expired
	 */
	Collection<SessionData> expired(long now);

}
