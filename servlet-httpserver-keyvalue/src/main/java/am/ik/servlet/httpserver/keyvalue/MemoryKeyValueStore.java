package am.ik.servlet.httpserver.keyvalue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import am.ik.rontolisp.wit.WitException;

/**
 * A key-value store in memory, which lasts as long as the object: what a JDK, and
 * rontolisp's interpreter and JVM, find for {@link KeyValueStore} where nothing else is
 * listed. A WebAssembly component's host is its store, and this class goes unused there.
 * The default store {@code ""} is the only one; the keys are listed a page of
 * {@value #PAGE} at a time, in their order, the cursor the last key of a page.
 */
public final class MemoryKeyValueStore implements KeyValueStore {

	/** The keys a page holds at most. */
	static final int PAGE = 100;

	private final TreeMap<String, byte[]> pairs = new TreeMap<>();

	private final int page;

	/** A store with nothing in it. */
	public MemoryKeyValueStore() {
		this(PAGE);
	}

	MemoryKeyValueStore(int page) {
		this.page = page;
	}

	@Override
	public Bucket open(String identifier) {
		if (!identifier.isEmpty()) {
			throw new WitException("no store " + identifier, new KeyValueStore.Error.NoSuchStore());
		}
		TreeMap<String, byte[]> pairs = this.pairs;
		int page = this.page;
		return new Bucket() {

			@Override
			public Optional<byte[]> get(String key) {
				synchronized (pairs) {
					byte[] value = pairs.get(key);
					return (value != null) ? Optional.of(Arrays.copyOf(value, value.length)) : Optional.empty();
				}
			}

			@Override
			public void set(String key, byte[] value) {
				synchronized (pairs) {
					pairs.put(key, Arrays.copyOf(value, value.length));
				}
			}

			@Override
			public void delete(String key) {
				synchronized (pairs) {
					pairs.remove(key);
				}
			}

			@Override
			public KeyResponse listKeys(Optional<String> cursor) {
				synchronized (pairs) {
					SortedMap<String, byte[]> after = cursor.isPresent() ? pairs.tailMap(cursor.get(), false) : pairs;
					List<String> keys = new ArrayList<>();
					for (String key : after.keySet()) {
						if (keys.size() == page) {
							return new KeyResponse(keys, Optional.of(keys.getLast()));
						}
						keys.add(key);
					}
					return new KeyResponse(keys, Optional.empty());
				}
			}

			@Override
			public void close() {
			}

		};
	}

}
