package am.ik.servlet.httpserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a session is, as a {@link SessionStore} keeps it: its id, its times, its interval
 * and its attributes. The container changes it and hands it back to the store after every
 * change; a store that keeps the object itself shares it between the requests of the
 * session, one that copies it hands every request a copy of its own.
 */
public final class SessionData {

	private String id;

	private final long creationTime;

	private long accessedTime;

	private long lastAccessedTime;

	private int maxInactiveInterval;

	private boolean fresh;

	private final Map<String, Object> attributes = new ConcurrentHashMap<>();

	/**
	 * A session being invalidated: its listeners are told, its attributes still there.
	 */
	static final int EXPIRING = 1;

	/** A session invalidated. */
	static final int INVALID = 2;

	/**
	 * Valid (0), {@link #EXPIRING} or {@link #INVALID}: every request holding this object
	 * sees it change.
	 */
	volatile int state;

	/**
	 * A session as a store read it back, or as the container makes one.
	 * @param id the session's id
	 * @param creationTime when it was made, in milliseconds since the epoch
	 * @param accessedTime when the latest request of the session was received
	 * @param lastAccessedTime when the request before it was received, or the creation
	 * time
	 * @param maxInactiveInterval the seconds between two requests before the session
	 * expires; zero or less for never
	 * @param fresh whether the client has not yet sent the id back
	 */
	public SessionData(String id, long creationTime, long accessedTime, long lastAccessedTime, int maxInactiveInterval,
			boolean fresh) {
		this.id = id;
		this.creationTime = creationTime;
		this.accessedTime = accessedTime;
		this.lastAccessedTime = lastAccessedTime;
		this.maxInactiveInterval = maxInactiveInterval;
		this.fresh = fresh;
	}

	public String getId() {
		return this.id;
	}

	void setId(String id) {
		this.id = id;
	}

	public long getCreationTime() {
		return this.creationTime;
	}

	public long getAccessedTime() {
		return this.accessedTime;
	}

	public long getLastAccessedTime() {
		return this.lastAccessedTime;
	}

	public int getMaxInactiveInterval() {
		return this.maxInactiveInterval;
	}

	void setMaxInactiveInterval(int maxInactiveInterval) {
		this.maxInactiveInterval = maxInactiveInterval;
	}

	/**
	 * Whether the client has not yet sent the session's id back.
	 * @return true until a request names the session
	 */
	public boolean isFresh() {
		return this.fresh;
	}

	/**
	 * The attributes by their names. A store that copies the session reads them to keep
	 * them and puts them back here when it reads the session back.
	 * @return the attributes, which the container changes
	 */
	public Map<String, Object> getAttributes() {
		return this.attributes;
	}

	/**
	 * Whether the session expired: its interval passed since its latest request.
	 * @param now the time in milliseconds since the epoch
	 * @return true if the session is to be invalidated
	 */
	public boolean isExpired(long now) {
		return this.maxInactiveInterval > 0 && now - this.accessedTime >= this.maxInactiveInterval * 1000L;
	}

	/** A request of the session was received. */
	void accessed(long now, boolean named) {
		this.lastAccessedTime = this.accessedTime;
		this.accessedTime = now;
		if (named) {
			this.fresh = false;
		}
	}

}
