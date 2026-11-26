package am.ik.servlet.httpserver.keyvalue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import am.ik.servlet.httpserver.SessionData;
import am.ik.servlet.httpserver.SessionStore;
import org.jspecify.annotations.Nullable;

/**
 * The sessions in a key-value store ({@code wasi:keyvalue}), which outlive the instance,
 * and the process, that made them: a session is the value of the key {@value #PREFIX}
 * followed by its id, written whole by every change ({@link SessionCodec}). The server
 * finds this store as a service provider of {@link SessionStore}, so an application whose
 * class path, or source path, holds this library keeps its sessions here with no code of
 * its own.
 *
 * <p>
 * The store is the default one ({@code ""}), opened by every call and closed again (a
 * host may refuse an instance a handle kept across requests). A write is what such a
 * store charges for and limits, so a request that only reads its session leaves the
 * access time as kept until it moved by a tenth of the session's interval, at most a
 * minute. The expired sessions are looked for over every instance sharing the store a
 * turn at a time: a turn reads at most {@value #SWEEP_READS} sessions, the next starts
 * where it stopped a minute later, and once every key was gone over the next pass starts
 * ten minutes later. When, and where, is the value of the key {@value #SWEEP}.
 */
public final class KeyValueSessionStore implements SessionStore {

	/** What the key of a session starts with. */
	static final String PREFIX = "servlet-httpserver:session:";

	/** The key holding when the expired sessions are next looked for. */
	static final String SWEEP = "servlet-httpserver:sweep";

	/** The time between two passes over the keys for the expired sessions. */
	static final long SWEEP_MILLIS = 600_000;

	/** The time between two turns of one pass. */
	static final long SWEEP_TURN_MILLIS = 60_000;

	/** The sessions a turn reads at most. */
	static final int SWEEP_READS = 16;

	/** The most an access time moves unwritten. */
	static final long ACCESS_STEP_MILLIS = 60_000;

	private final KeyValueStore store;

	/**
	 * The sessions in the first key-value store {@code ServiceLoader} finds: the host's
	 * in a WebAssembly component, else the first a {@code META-INF/services} file lists.
	 */
	public KeyValueSessionStore() {
		this(ServiceLoader.load(KeyValueStore.class)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("no " + KeyValueStore.class.getName() + " is provided")));
	}

	/**
	 * The sessions in a key-value store.
	 * @param store the store
	 */
	public KeyValueSessionStore(KeyValueStore store) {
		this.store = store;
	}

	@Override
	public @Nullable SessionData load(String id) {
		try (KeyValueStore.Bucket bucket = open()) {
			Optional<byte[]> value = bucket.get(PREFIX + id);
			return value.isPresent() ? SessionCodec.decode(id, value.get()) : null;
		}
	}

	@Override
	public void save(SessionData session) {
		byte[] value = SessionCodec.encode(session);
		try (KeyValueStore.Bucket bucket = open()) {
			bucket.set(PREFIX + session.getId(), value);
		}
	}

	/**
	 * Writes a session whose one change is a request received only where its access time
	 * moved by a tenth of its interval since the store kept it, at most a minute.
	 */
	@Override
	public void saveAccess(SessionData session) {
		long interval = session.getMaxInactiveInterval() * 1000L;
		long step = (interval > 0) ? Math.min(interval / 10, ACCESS_STEP_MILLIS) : ACCESS_STEP_MILLIS;
		if (session.getAccessedTime() - session.getLastAccessedTime() >= step) {
			save(session);
		}
	}

	@Override
	public void delete(String id) {
		try (KeyValueStore.Bucket bucket = open()) {
			bucket.delete(PREFIX + id);
		}
	}

	/**
	 * The expired sessions of a turn: at most {@value #SWEEP_READS} sessions read, from
	 * the key the turn before stopped at. A value under the prefix that is no session of
	 * this format is deleted. The value of {@value #SWEEP} is the time of the next turn
	 * and the place in the keys it starts at, their number before it: no host's cursor,
	 * which may not outlive the minute. A turn that fails leaves the next pass to start
	 * over.
	 */
	@Override
	public Collection<SessionData> expired(long now) {
		List<SessionData> expired = new ArrayList<>();
		try (KeyValueStore.Bucket bucket = open()) {
			long next = 0;
			long from = 0;
			Optional<byte[]> sweep = bucket.get(SWEEP);
			if (sweep.isPresent()) {
				String value = new String(sweep.get(), StandardCharsets.UTF_8);
				int space = value.indexOf(' ');
				next = Long.parseLong((space < 0) ? value : value.substring(0, space));
				from = (space < 0) ? 0 : Long.parseLong(value.substring(space + 1));
			}
			if (now < next) {
				return expired;
			}
			bucket.set(SWEEP, Long.toString(now + SWEEP_MILLIS).getBytes(StandardCharsets.UTF_8));
			long at = 0;
			int reads = 0;
			Optional<String> cursor = Optional.empty();
			do {
				KeyValueStore.KeyResponse page = bucket.listKeys(cursor);
				for (String key : page.keys()) {
					if (at++ < from || !key.startsWith(PREFIX)) {
						continue;
					}
					if (reads == SWEEP_READS) {
						bucket.set(SWEEP,
								((now + SWEEP_TURN_MILLIS) + " " + (at - 1)).getBytes(StandardCharsets.UTF_8));
						return expired;
					}
					reads++;
					Optional<byte[]> value = bucket.get(key);
					if (value.isPresent()) {
						SessionData session = SessionCodec.decode(key.substring(PREFIX.length()), value.get());
						if (session == null) {
							bucket.delete(key);
						}
						else if (session.isExpired(now)) {
							expired.add(session);
						}
					}
				}
				cursor = page.cursor();
			}
			while (cursor.isPresent());
		}
		return expired;
	}

	private KeyValueStore.Bucket open() {
		return this.store.open("");
	}

}
