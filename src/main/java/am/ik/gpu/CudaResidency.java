package am.ik.gpu;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Device residency for the CUDA backend: a cache from a HOST array to a device buffer
 * that holds a copy of it, so that a call whose operand was uploaded -- or PRODUCED -- by
 * a recent call does not upload it again.
 *
 * <h2>What it is, and what it is not</h2>
 *
 * It is a cache and not an ownership transfer. The host array stays authoritative: an
 * element read on the JVM class output is a raw {@code daload} with no seam to intercept,
 * so a device copy whose host array was not also written would be unsound. Hence every
 * device result is DOWNLOADED into its host array exactly as before, and what residency
 * removes is the HOST-TO-DEVICE half of the round trip only -- the half that measured as
 * 63% of the copy time in a {@code --gpu --simd} training step ({@code .kb/gpu.md}).
 *
 * <h2>The key is the IDENTITY of the primitive array, held WEAKLY</h2>
 *
 * Identity is the one mechanism that exists on both interceptors: the interpreter's
 * packed array is a record over a {@code double[]} / {@code float[]}, and the JVM class
 * output's packed array IS a bare {@code double[]} / {@code float[]} with its dimension
 * header inside it. An entry records the element span it mirrors ({@code offset},
 * {@code bytes}) as well, and a lookup at a different span is a miss, not a partial hit.
 *
 * <p>
 * The key is a {@link WeakReference} to the array, and that is not a refinement but the
 * difference between a win and a loss. The first version held its keys strongly, and on a
 * {@code --gpu --simd} training step it made the step 2.3x SLOWER: every activation and
 * gradient the step allocates stayed reachable from the cache until the LRU got round to
 * it, so the Java heap grew to 14 GB, every fresh result array landed on pages nothing
 * had touched, and the device-to-host copies into them went from a quarter of the step to
 * two thirds of it -- while the driver's pool grew by the same gigabytes, one cold
 * allocation at a time. A cache keyed on an array's identity has no meaning once the
 * array is unreachable, so the entry dies with the array: a collected key turns up on the
 * {@link ReferenceQueue}, and {@link #drain()} frees its buffer. The byte budget below is
 * then a CAP, not the mechanism -- it bounds the device memory a program that keeps
 * everything reachable can pin.
 *
 * <h2>Invalidation is the caller's duty, and it is enumerated</h2>
 *
 * An entry is valid only while its host array has not been written since the copy was
 * made. Every in-place write to a packed float array on either backend calls
 * {@link Gpu#written(Object)}, which drops the entry; the enumeration of those writers is
 * in {@code .kb/gpu.md} ("The residency design"). Writing through a path that is not
 * enumerated is a silent wrong answer, which is why the enumeration is pinned by a test
 * on each backend that writes through EVERY setter after a device op and reads the right
 * answer back. A device result is inserted AFTER its download, when device and host hold
 * the same bytes; an operand after its upload, for the same reason.
 *
 * <h2>The release policy</h2>
 *
 * Entries die with their arrays (above), and an LRU against a byte budget the owning
 * {@link CudaGemm} derives from free device memory (a quarter of it, refreshed whenever
 * the pre-flight re-reads {@code cuMemGetInfo}) bounds what a program that keeps its
 * arrays reachable can hold. Nothing is freed from inside this class: a dropped, evicted
 * or collected entry's buffer goes onto a PENDING list that the device code drains with
 * {@code cuMemFreeAsync} at the two moments it is safe to -- the start of a call, before
 * any operand is looked up, and the end of one, after the launch and the download. A free
 * enqueued on the null stream BETWEEN an operand's lookup and its launch would be ordered
 * before the kernel that reads it, and that is the one ordering this class exists to
 * forbid. It also keeps {@link Gpu#written(Object)}, which runs on whichever thread wrote
 * the array, free of driver calls: a host write never needs a CUDA context.
 *
 * <h2>Cost on the write path</h2>
 *
 * {@link #written(Object)} is called once per element store from an {@code aset} loop, so
 * the empty case is a volatile read and nothing else; a non-empty cache pays one
 * uncontended monitor and one identity lookup.
 */
final class CudaResidency {

	/**
	 * A weakly held host array, hashed by identity. {@link #equals} is written for the
	 * one caller it has -- {@code HashMap} asking whether a stored key is the one looked
	 * up -- so it matches itself, or another key whose referent is the same live array;
	 * two keys whose arrays are gone never match, which is what lets a collected key be
	 * removed by its own identity.
	 */
	static final class Key extends WeakReference<Object> {

		private final int hash;

		Key(Object referent, ReferenceQueue<Object> queue) {
			super(referent, queue);
			this.hash = System.identityHashCode(referent);
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof Key key)) {
				return false;
			}
			Object mine = get();
			return mine != null && mine == key.get();
		}

	}

	/**
	 * The transient key a lookup presents: the live array itself, so a lookup allocates
	 * no {@link WeakReference}. Equal to a stored {@link Key} whose referent it is.
	 */
	private static final class Lookup {

		private final Object host;

		Lookup(Object host) {
			this.host = host;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.host);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof Key key && key.get() == this.host;
		}

	}

	/**
	 * One resident copy: the device pointer, and the host span it mirrors. A pointer of
	 * {@code 0} is not a copy but a MARK -- the span was offered once to a member that
	 * uploads only on a second sight ({@link #offeredBefore}) -- and holds no device
	 * memory, so it counts for nothing in the budget and frees nothing when dropped.
	 */
	record Entry(long pointer, long offset, long bytes) {
	}

	private final ReferenceQueue<Object> collected = new ReferenceQueue<>();

	private final LinkedHashMap<Object, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

	private final List<Long> pending = new ArrayList<>();

	private long bytes;

	private long budget;

	/** The cheap gate for {@link #written}: {@code true} while any entry exists. */
	private volatile boolean occupied;

	/**
	 * The array {@link #written} last dropped (or looked for and found absent), so that
	 * an element loop storing into one array pays the lock once rather than once per
	 * element: while nothing has been inserted since, that array is certainly not
	 * resident. Any {@link #put} clears it, because the array may be resident again.
	 */
	private volatile @Nullable Object lastDropped;

	private long hits;

	private long misses;

	/**
	 * The device pointer holding a copy of {@code host}'s elements from byte
	 * {@code offset} for {@code bytes}, or {@code 0} when none is resident (a device
	 * pointer is never 0). A hit becomes the most recently used entry.
	 * @param host the host array
	 * @param offset the first byte of the span
	 * @param bytes the length of the span
	 * @return the device pointer, or 0
	 */
	synchronized long lookup(Object host, long offset, long bytes) {
		Entry entry = this.entries.get(new Lookup(host));
		if (entry != null && entry.pointer != 0 && entry.offset == offset && entry.bytes == bytes) {
			this.hits++;
			return entry.pointer;
		}
		this.misses++;
		return 0;
	}

	/**
	 * Whether {@code host}'s span has been offered through here before and not written
	 * since -- and if not, remembers that it has now. The accept-on-second-sight rule of
	 * the matrix-by-vector product ({@code CudaGemm.gemv}): a GEMV over a matrix that is
	 * not resident loses to the CPU, so the first offer of a matrix declines and leaves
	 * this mark, the second uploads it, and a matrix written in between -- which drops
	 * the mark exactly as it drops a copy -- is never uploaded at all. The mark is an
	 * {@link Entry} with no buffer, so it shares the weak key, the write invalidation and
	 * the LRU with the copies, and costs nothing on the device.
	 * @param host the host array
	 * @param offset the first byte of the span
	 * @param bytes the length of the span
	 * @return {@code true} when the same span was offered before and is still unwritten
	 */
	synchronized boolean offeredBefore(Object host, long offset, long bytes) {
		expunge();
		Entry entry = this.entries.get(new Lookup(host));
		if (entry != null && entry.pointer == 0 && entry.offset == offset && entry.bytes == bytes) {
			return true;
		}
		if (entry != null) {
			this.entries.remove(new Lookup(host));
			drop(entry);
		}
		this.entries.put(new Key(host, this.collected), new Entry(0, offset, bytes));
		this.lastDropped = null;
		this.occupied = true;
		return false;
	}

	/**
	 * Forgets one entry's device memory: a real copy's buffer goes onto the pending list
	 * and out of the byte count; a mark holds neither.
	 */
	private void drop(Entry entry) {
		if (entry.pointer != 0) {
			this.pending.add(entry.pointer);
			this.bytes -= entry.bytes;
		}
	}

	/**
	 * Records that {@code pointer} now holds a copy of {@code host}'s span, replacing any
	 * entry the array had, and evicts least-recently-used entries while the cache is over
	 * its budget. Replaced and evicted pointers go onto the pending list.
	 * @param host the host array
	 * @param offset the first byte of the span
	 * @param bytes the length of the span
	 * @param pointer the device buffer, which the cache owns from now on
	 */
	synchronized void put(Object host, long offset, long bytes, long pointer) {
		expunge();
		Entry previous = this.entries.remove(new Lookup(host));
		if (previous != null) {
			drop(previous);
		}
		this.entries.put(new Key(host, this.collected), new Entry(pointer, offset, bytes));
		this.bytes += bytes;
		this.lastDropped = null;
		Iterator<Map.Entry<Object, Entry>> oldest = this.entries.entrySet().iterator();
		while (this.bytes > this.budget && oldest.hasNext()) {
			Entry evicted = oldest.next().getValue();
			oldest.remove();
			drop(evicted);
		}
		this.occupied = !this.entries.isEmpty();
	}

	/**
	 * The host array was written: its resident copy, if any, is stale and is dropped. The
	 * buffer is not freed here -- see the class comment -- but queued.
	 * @param host the host array that was written
	 */
	void written(Object host) {
		if (!this.occupied || host == this.lastDropped) {
			return;
		}
		synchronized (this) {
			Entry entry = this.entries.remove(new Lookup(host));
			if (entry != null) {
				drop(entry);
				this.occupied = !this.entries.isEmpty();
			}
			this.lastDropped = host;
		}
	}

	/**
	 * Drops every entry. The pointers are queued, not freed; the caller drains them.
	 */
	synchronized void evictAll() {
		evictAll(new long[0]);
	}

	/**
	 * Drops every entry except the ones whose pointer is in {@code keep} -- the operands
	 * the call in progress has already looked up, which its launch is about to read and
	 * which must therefore not be queued for freeing underneath it. A zero in
	 * {@code keep} matches nothing.
	 * @param keep device pointers to leave resident
	 */
	synchronized void evictAll(long[] keep) {
		Iterator<Map.Entry<Object, Entry>> each = this.entries.entrySet().iterator();
		while (each.hasNext()) {
			Entry entry = each.next().getValue();
			boolean kept = false;
			for (long pointer : keep) {
				kept |= pointer != 0 && pointer == entry.pointer;
			}
			if (!kept) {
				each.remove();
				drop(entry);
			}
		}
		this.occupied = !this.entries.isEmpty();
	}

	/**
	 * Hands over every pointer dropped, replaced, evicted or orphaned by a collected
	 * array since the last drain, for the caller to free, and forgets them.
	 * @return the pointers to free, possibly empty
	 */
	synchronized long[] drain() {
		expunge();
		if (this.pending.isEmpty()) {
			return new long[0];
		}
		long[] pointers = new long[this.pending.size()];
		for (int i = 0; i < pointers.length; i++) {
			pointers[i] = this.pending.get(i);
		}
		this.pending.clear();
		return pointers;
	}

	/**
	 * Moves the entries whose arrays the collector has reclaimed onto the pending list. A
	 * collected key is removed by its own identity, which is the one thing its
	 * {@code equals} still answers.
	 */
	private void expunge() {
		Object key;
		boolean any = false;
		while ((key = this.collected.poll()) != null) {
			Entry entry = this.entries.remove(key);
			if (entry != null) {
				drop(entry);
				any = true;
			}
		}
		if (any) {
			this.occupied = !this.entries.isEmpty();
		}
	}

	/** Sets the byte budget; entries over it are evicted on the next {@link #put}. */
	synchronized void setBudget(long budget) {
		this.budget = budget;
	}

	/** The budget in force. */
	synchronized long budget() {
		return this.budget;
	}

	/** Bytes currently resident (pending frees excluded). */
	synchronized long bytes() {
		return this.bytes;
	}

	/** Whether anything is resident. */
	boolean occupied() {
		return this.occupied;
	}

	/** Lookups answered from the cache since the process started; for the tests. */
	synchronized long hits() {
		return this.hits;
	}

	/** Lookups that missed since the process started; for the tests. */
	synchronized long misses() {
		return this.misses;
	}

}
