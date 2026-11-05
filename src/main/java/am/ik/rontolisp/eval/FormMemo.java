package am.ik.rontolisp.eval;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * What the interpreter remembers about a form, by the identity of its cons, for as long
 * as the form can be evaluated again. A form a program keeps (a function's body, an
 * expansion another entry holds) keeps its entry, and a form a program built for one
 * {@code eval} leaves with its garbage, so there is no bound for a big system to fall off
 * ({@code .kb/interpreter-expansion-memo.md}).
 *
 * <p>
 * A read takes no lock: it is one step of nearly every form the interpreter runs. A
 * writer holds this object's monitor and publishes an entry with release semantics, and a
 * reader racing it misses the entry and computes the answer again, which every caller
 * tolerates (two threads racing on one call site both expand, the last write wins).
 *
 * @param <V> what is remembered
 */
final class FormMemo<V> {

	private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Entry[].class);

	private static final int INITIAL_CAPACITY = 1 << 6;

	private final ReferenceQueue<LispVal> dropped = new ReferenceQueue<>();

	@SuppressWarnings("unchecked")
	private volatile @Nullable Entry<V>[] table = new Entry[INITIAL_CAPACITY];

	private int size;

	/**
	 * What is remembered about a form.
	 * @param form the form
	 * @return what was put for this very cons, or null
	 */
	@SuppressWarnings("unchecked")
	@Nullable V get(LispVal form) {
		@Nullable Entry<V>[] entries = this.table;
		int hash = System.identityHashCode(form);
		Entry<V> entry = (Entry<V>) SLOT.getAcquire(entries, hash & (entries.length - 1));
		while (entry != null) {
			if (entry.hash == hash && entry.get() == form) {
				return entry.value;
			}
			entry = entry.next;
		}
		return null;
	}

	/**
	 * Remembers a value for a form, replacing what was there.
	 * @param form the form
	 * @param value what to remember
	 */
	synchronized void put(LispVal form, V value) {
		expunge();
		remove(form, System.identityHashCode(form));
		if (this.size >= this.table.length - (this.table.length >> 2)) {
			grow();
		}
		add(this.table, form, value);
		this.size++;
	}

	/**
	 * Forgets everything: entries still reachable from a concurrent read are its answer
	 * to a question asked before this call.
	 */
	@SuppressWarnings("unchecked")
	synchronized void clear() {
		this.table = new Entry[INITIAL_CAPACITY];
		this.size = 0;
	}

	/**
	 * Whether nothing is remembered.
	 * @return true when no form has an entry
	 */
	synchronized boolean isEmpty() {
		return this.size == 0;
	}

	/**
	 * The number of forms remembered, a form that became garbage counting until an
	 * insertion notices.
	 * @return the entry count
	 */
	synchronized int size() {
		return this.size;
	}

	/** Unlinks the entries whose forms the collector found unreachable. */
	@SuppressWarnings("unchecked")
	private void expunge() {
		Reference<? extends LispVal> reference = this.dropped.poll();
		while (reference != null) {
			Entry<V> gone = (Entry<V>) reference;
			@Nullable Entry<V>[] entries = this.table;
			int index = gone.hash & (entries.length - 1);
			Entry<V> previous = null;
			for (Entry<V> entry = entries[index]; entry != null; entry = entry.next) {
				if (entry == gone) {
					unlink(entries, index, previous, entry);
					this.size--;
					break;
				}
				previous = entry;
			}
			reference = this.dropped.poll();
		}
	}

	private void remove(LispVal form, int hash) {
		@Nullable Entry<V>[] entries = this.table;
		int index = hash & (entries.length - 1);
		Entry<V> previous = null;
		for (Entry<V> entry = entries[index]; entry != null; entry = entry.next) {
			if (entry.hash == hash && entry.get() == form) {
				unlink(entries, index, previous, entry);
				this.size--;
				return;
			}
			previous = entry;
		}
	}

	private void unlink(@Nullable Entry<V>[] entries, int index, @Nullable Entry<V> previous, Entry<V> entry) {
		if (previous == null) {
			SLOT.setRelease(entries, index, entry.next);
		}
		else {
			previous.next = entry.next;
		}
	}

	/**
	 * Copies into a table twice the size, in new entries: a concurrent read may be
	 * walking a chain of the old table, and an old entry relinked would lead it into
	 * another bucket.
	 */
	@SuppressWarnings("unchecked")
	private void grow() {
		@Nullable Entry<V>[] entries = this.table;
		@Nullable Entry<V>[] grown = new Entry[entries.length << 1];
		int live = 0;
		for (Entry<V> head : entries) {
			for (Entry<V> entry = head; entry != null; entry = entry.next) {
				LispVal form = entry.get();
				if (form != null) {
					add(grown, form, entry.value);
					live++;
				}
			}
		}
		this.size = live;
		this.table = grown;
	}

	@SuppressWarnings("unchecked")
	private void add(@Nullable Entry<V>[] entries, LispVal form, V value) {
		int hash = System.identityHashCode(form);
		int index = hash & (entries.length - 1);
		Entry<V> entry = new Entry<>(form, hash, value, entries[index], this.dropped);
		SLOT.setRelease(entries, index, entry);
	}

	private static final class Entry<V> extends WeakReference<LispVal> {

		final int hash;

		final V value;

		@Nullable Entry<V> next;

		Entry(LispVal form, int hash, V value, @Nullable Entry<V> next, ReferenceQueue<LispVal> queue) {
			super(form, queue);
			this.hash = hash;
			this.value = value;
			this.next = next;
		}

	}

}
