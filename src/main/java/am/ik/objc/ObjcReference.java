package am.ik.objc;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

import org.jspecify.annotations.Nullable;

/**
 * The references a Lisp pointer value holds on one Objective-C object -- the HANDLE of
 * the new base's {@code objc-object-pointer} (.kb/objc.md, "The new base: ownership") --
 * and the table that makes one pointer value per address.
 *
 * <p>
 * Three counts, kept here rather than in the Lisp value because a value's slots are part
 * of its {@code equal} hash: {@link #GC} (released on thread 0 when this handle is
 * collected), {@link #MANUAL} (explicit retains; never released by the collector) and
 * {@link #POOLED} (held by an emulated autorelease pool). The Lisp layer decides every
 * rule; this class only counts, refuses to count below zero, and releases what
 * {@link #GC} still holds when the handle dies.
 *
 * <p>
 * The table maps an address to the Lisp value made for it, WEAKLY, so the value -- and
 * with it the handle and its references -- dies when the program drops it, and the next
 * answer for that address makes a fresh one. A value is an opaque {@link Object}: the
 * interpreter's instance or the compiled program's.
 */
public final class ObjcReference {

	/** The count released when the handle is collected. */
	public static final int GC = 0;

	/** The count of explicit retains. */
	public static final int MANUAL = 1;

	/** The count an emulated autorelease pool holds. */
	public static final int POOLED = 2;

	private static final Cleaner CLEANER = Cleaner.create();

	private static final Map<Long, Entry> INTERNED = new ConcurrentHashMap<>();

	/** Where a collected value's entry is queued, so the table sheds dead addresses. */
	private static final ReferenceQueue<Object> DEAD = new ReferenceQueue<>();

	private final long address;

	private final AtomicLongArray counts;

	private ObjcReference(long address, AtomicLongArray counts) {
		this.address = address;
		this.counts = counts;
	}

	/**
	 * A handle holding {@code gc} references the collector will release.
	 * @param runtime the binding the release goes through
	 * @param address the object's address
	 * @param gc the references handed over
	 * @return the handle
	 */
	public static ObjcReference own(ObjcRuntime runtime, long address, long gc) {
		AtomicLongArray counts = new AtomicLongArray(3);
		counts.set(GC, gc);
		ObjcReference handle = new ObjcReference(address, counts);
		// The cleaning action must not reference the handle it is registered for.
		CLEANER.register(handle, () -> {
			long held = counts.getAndSet(GC, 0);
			try {
				for (long i = 0; i < held; i++) {
					runtime.releaseOnMain(address);
				}
			}
			catch (RuntimeException ignored) {
				// the process is on its way out, or the pump is gone: leaking is the
				// safe direction
			}
		});
		return handle;
	}

	/**
	 * The object's address.
	 * @return the address
	 */
	public long address() {
		return this.address;
	}

	/**
	 * Adds {@code delta} to one count, refusing to go below zero.
	 * @param which {@link #GC}, {@link #MANUAL} or {@link #POOLED}
	 * @param delta the change
	 * @return the new count, or -1 (and no change) when it would go below zero
	 */
	public long adjust(int which, long delta) {
		while (true) {
			long current = this.counts.get(which);
			long next = current + delta;
			if (next < 0) {
				return -1;
			}
			if (this.counts.compareAndSet(which, current, next)) {
				return next;
			}
		}
	}

	/**
	 * The live value made for an address, or {@code null}.
	 * @param address the object's address
	 * @return the value, or {@code null} when none is alive
	 */
	public static @Nullable Object interned(long address) {
		Entry ref = INTERNED.get(address);
		return ref == null ? null : ref.get();
	}

	/**
	 * Records the value for an address unless a live one is there already.
	 * @param address the object's address
	 * @param value the fresh value
	 * @return the value the table now holds: {@code value}, or the one that won
	 */
	public static Object intern(long address, Object value) {
		purge();
		while (true) {
			Entry fresh = new Entry(address, value);
			Entry existing = INTERNED.putIfAbsent(address, fresh);
			if (existing == null) {
				return value;
			}
			Object winner = existing.get();
			if (winner != null) {
				return winner;
			}
			if (INTERNED.replace(address, existing, fresh)) {
				return value;
			}
		}
	}

	private static void purge() {
		Reference<?> dead;
		while ((dead = DEAD.poll()) != null) {
			if (dead instanceof Entry entry) {
				INTERNED.remove(entry.address, entry);
			}
		}
	}

	/** A table entry: the value, weakly, and the address it is filed under. */
	private static final class Entry extends WeakReference<Object> {

		private final long address;

		Entry(long address, Object value) {
			super(value, DEAD);
			this.address = address;
		}

	}

	@Override
	public String toString() {
		return "#<objc-reference " + Long.toHexString(this.address) + ">";
	}

}
