package am.ik.rontolisp.eval;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * A concurrent set of names that counts its changes, so what was worked out from it can
 * be kept until it changes ({@link #epoch}): the interpreter's proclaimed specials, which
 * every variable reference asks about (a note on the symbol, {@code LispEvaluator}).
 */
final class CountedNames extends AbstractSet<String> {

	private final Set<String> names = ConcurrentHashMap.newKeySet();

	private volatile int epoch;

	/**
	 * How many changes the set has seen.
	 * @return the count, which moves on every change
	 */
	int epoch() {
		return this.epoch;
	}

	@Override
	public boolean add(String name) {
		boolean added = this.names.add(name);
		if (added) {
			changed();
		}
		return added;
	}

	@Override
	public boolean remove(@Nullable Object name) {
		boolean removed = this.names.remove(name);
		if (removed) {
			changed();
		}
		return removed;
	}

	@Override
	public boolean contains(@Nullable Object name) {
		return this.names.contains(name);
	}

	@Override
	public int size() {
		return this.names.size();
	}

	@Override
	public Iterator<String> iterator() {
		Iterator<String> each = this.names.iterator();
		return new Iterator<>() {

			@Override
			public boolean hasNext() {
				return each.hasNext();
			}

			@Override
			public String next() {
				return each.next();
			}

			@Override
			public void remove() {
				each.remove();
				changed();
			}

		};
	}

	private synchronized void changed() {
		this.epoch++;
	}

}
