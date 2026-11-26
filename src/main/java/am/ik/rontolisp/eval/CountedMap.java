package am.ik.rontolisp.eval;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A map that counts its writes, so what was worked out from it can be kept until it
 * changes ({@link #epoch}): the interpreter's struct accessor and setf expander tables,
 * which a {@code setf} expansion reads ({@code LispEvaluator.placeEpoch}). Its views are
 * read-only: every write goes through {@link #put}, {@link #remove} or {@link #clear},
 * each moving the count after the write.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
final class CountedMap<K, V> extends AbstractMap<K, V> {

	private final Map<K, V> map = new HashMap<>();

	private volatile int epoch;

	/**
	 * How many writes the map has seen.
	 * @return the count, which moves after every write
	 */
	int epoch() {
		return this.epoch;
	}

	@Override
	public @Nullable V put(K key, V value) {
		V previous = this.map.put(key, value);
		changed();
		return previous;
	}

	@Override
	public @Nullable V remove(@Nullable Object key) {
		boolean present = this.map.containsKey(key);
		V previous = this.map.remove(key);
		if (present) {
			changed();
		}
		return previous;
	}

	@Override
	public void clear() {
		this.map.clear();
		changed();
	}

	@Override
	public @Nullable V get(@Nullable Object key) {
		return this.map.get(key);
	}

	@Override
	public boolean containsKey(@Nullable Object key) {
		return this.map.containsKey(key);
	}

	@Override
	public int size() {
		return this.map.size();
	}

	@Override
	public boolean isEmpty() {
		return this.map.isEmpty();
	}

	@Override
	public Set<Map.Entry<K, V>> entrySet() {
		return Collections.unmodifiableMap(this.map).entrySet();
	}

	private synchronized void changed() {
		this.epoch++;
	}

}
