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
 * Device residency, shared by both backends: a cache from a HOST array to a device buffer
 * that holds a copy of it, so that a call whose operand was uploaded -- or PRODUCED -- by
 * a recent call does not upload it again. {@link CudaGemm} built it first (as
 * {@code CudaResidency}); {@link MetalGemm} adopted it unchanged when the Metal half grew
 * resident copies, because nothing in it is CUDA's -- a device buffer is a {@code long}
 * here whichever platform minted it (a {@code CUdeviceptr} on one, an {@code MTLBuffer}'s
 * address on the other), and the rules that make the cache sound (weak identity keys,
 * invalidation on every write, materialization before every read) are the interceptors',
 * not the driver's.
 *
 * <h2>Two kinds of entry: a copy, and the ONLY copy</h2>
 *
 * An entry is CLEAN when device and host hold the same bytes -- an operand after its
 * upload, a result after its download -- and the host array is then authoritative: a host
 * read needs nothing, and a host write drops the entry. Since {@code .todo/491} an entry
 * can also be DIRTY: the device holds the bytes and the host array does NOT -- a result a
 * member left on the device rather than downloading it, or an array a device member
 * updated in place. A dirty entry is the one place the library is the source of truth,
 * and every host read of that array has to {@linkplain #claimDirty materialize} it first;
 * the enumeration of those readers, on each interceptor, is in {@code .kb/gpu.md} ("A
 * result comes home on first host touch") and pinned by a test on each, exactly as the
 * writers are. The device side never drops a dirty entry on its own: every path that
 * removes one -- an eviction, a release, a replacement at a different span -- hands the
 * buffer back to the owning device as a {@link Flush} to DOWNLOAD before it is freed, and
 * the device does so at once, before it returns to its caller. A dirty entry whose host
 * array has been collected holds bytes nobody can read, and is simply freed.
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
 * <h2>Invalidation and materialization are the caller's duty, and both are
 * enumerated</h2>
 *
 * A clean entry is valid only while its host array has not been written since the copy
 * was made, and a dirty one only until the host reads or writes the array. Every in-place
 * write to a packed float array on either backend calls {@link Gpu#written(Object)}
 * BEFORE the write, which brings a dirty copy home and then drops the entry; every host
 * read calls {@link Gpu#materialize(Object)} first. Writing or reading through a path
 * that is not enumerated is a silent wrong answer, which is why both enumerations are
 * pinned by a test on each backend.
 *
 * <h2>The release policy</h2>
 *
 * Entries die with their arrays (above), and an LRU against a byte budget the owning
 * device derives -- {@link CudaGemm} from free device memory (a quarter of it, refreshed
 * whenever the pre-flight re-reads {@code cuMemGetInfo}), {@link MetalGemm} from its own
 * pool's budget -- bounds what a program that keeps its arrays reachable can hold. Clean
 * entries are evicted first, least recently used first; a dirty entry is evicted only
 * when no clean one is left, and by a {@link Flush} rather than a drop. Nothing is freed
 * from inside this class: a dropped, evicted or collected entry's buffer goes onto a
 * PENDING list that the device code drains at the moments it is safe to -- the start of a
 * call, before any operand is looked up, and the end of one, after the launch -- with
 * {@code cuMemFreeAsync} on CUDA, and by returning the slab to the pool on Metal. A free
 * enqueued on the null stream BETWEEN an operand's lookup and its launch would be ordered
 * before the kernel that reads it, and that is the one ordering this class exists to
 * forbid. It also keeps {@link Gpu#written(Object)}, which runs on whichever thread wrote
 * the array, free of driver calls when the entry is clean: a host write of a clean array
 * never needs a CUDA context or a Metal command queue.
 *
 * <h2>Cost on the read and write paths</h2>
 *
 * {@link #written(Object)} and {@link #claimDirty(Object)} are called once per element
 * store or load from an {@code aset} / {@code aref} loop, so the empty case of each is a
 * volatile read and nothing else; a non-empty cache pays one uncontended monitor and one
 * identity lookup, once per array rather than once per element, because each remembers
 * the array it last answered for.
 */
final class DeviceResidency {

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
	 * One resident copy: the device pointer, the host span it mirrors, and whether the
	 * device holds bytes the host does not ({@code dirty}). A pointer of {@code 0} is not
	 * a copy but a MARK -- the span was offered once to a member that uploads only on a
	 * second sight ({@link #offeredBefore}) -- and holds no device memory, so it counts
	 * for nothing in the budget and frees nothing when dropped.
	 */
	static final class Entry {

		final long pointer;

		final long offset;

		final long bytes;

		boolean dirty;

		Entry(long pointer, long offset, long bytes, boolean dirty) {
			this.pointer = pointer;
			this.offset = offset;
			this.bytes = bytes;
			this.dirty = dirty;
		}

	}

	/**
	 * A dirty copy the cache has let go of, for the owning device to DOWNLOAD into its
	 * host array and only then free -- an evicted, released or replaced entry whose bytes
	 * the host does not yet have. The host array is held strongly here so it cannot be
	 * collected between the decision and the download.
	 *
	 * @param host the host array the bytes belong in
	 * @param pointer the device buffer
	 * @param offset the first byte of the span in the host array
	 * @param bytes the length of the span
	 */
	record Flush(Object host, long pointer, long offset, long bytes) {
	}

	private final ReferenceQueue<Object> collected = new ReferenceQueue<>();

	private final LinkedHashMap<Object, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

	private final List<Long> pending = new ArrayList<>();

	private final List<Flush> flushes = new ArrayList<>();

	private long bytes;

	private long budget;

	/** How many entries are dirty; the cheap gate for {@link #claimDirty}. */
	private volatile int dirtyCount;

	/** The cheap gate for {@link #written}: {@code true} while any entry exists. */
	private volatile boolean occupied;

	/**
	 * How many arrays each of the two fast paths remembers. One was not enough: a loop
	 * that reads one array and writes another ({@code linalg:concatenate}'s defun, a
	 * typed {@code dotimes} over two arrays) alternates between them and took the lock on
	 * every element -- a third of a training step's samples. Four covers every loop the
	 * profile showed; a loop over more pays the monitor, which is still correct.
	 */
	private static final int RECENT = 4;

	/**
	 * The arrays {@link #written} recently dropped (or looked for and found absent), so
	 * that an element loop storing into them pays the lock once rather than once per
	 * element: while nothing has been inserted since, those arrays are certainly not
	 * resident. Any {@link #put} clears them, because an array may be resident again. A
	 * ring of {@link #RECENT} slots; a slot is a volatile read.
	 */
	private final @Nullable Object[] recentlyDropped = new @Nullable Object[RECENT];

	private volatile int droppedCursor;

	/**
	 * The arrays {@link #claimDirty} recently answered "clean" for (materialized, clean
	 * already, or absent), so that an element loop reading them pays the lock once. Any
	 * dirty insertion clears them, because an array may be dirty again.
	 */
	private final @Nullable Object[] recentlyClean = new @Nullable Object[RECENT];

	private volatile int cleanCursor;

	/** Whether {@code host} is in the ring; a volatile read per slot, no allocation. */
	private boolean recent(@Nullable Object[] ring, int cursor, Object host) {
		for (int i = 0; i < RECENT; i++) {
			if (ring[i] == host) {
				return true;
			}
		}
		return false;
	}

	/** Adds {@code host} to the ring; under the monitor, so the cursor is consistent. */
	private void remember(@Nullable Object[] ring, boolean dropped, Object host) {
		int cursor = dropped ? this.droppedCursor : this.cleanCursor;
		ring[cursor] = host;
		cursor = (cursor + 1) % RECENT;
		if (dropped) {
			this.droppedCursor = cursor;
		}
		else {
			this.cleanCursor = cursor;
		}
	}

	/** Empties a ring; under the monitor. */
	private void forget(@Nullable Object[] ring) {
		for (int i = 0; i < RECENT; i++) {
			ring[i] = null;
		}
	}

	private long hits;

	private long misses;

	/**
	 * The device pointer holding a copy of {@code host}'s elements from byte
	 * {@code offset} for {@code bytes}, or {@code 0} when none is resident (a device
	 * pointer is never 0). A hit becomes the most recently used entry. Clean or dirty
	 * alike: a device operand reads the device's bytes, which are the right ones either
	 * way.
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
	 * Whether a copy of {@code host} (at any span) is resident -- the question a member
	 * that pays only when its operand is already there asks before it unwraps anything.
	 * No hit or miss is counted, and no context is needed.
	 * @param host the host array
	 * @return {@code true} when a device buffer holds a copy of it
	 */
	boolean resident(Object host) {
		if (!this.occupied) {
			return false;
		}
		synchronized (this) {
			Entry entry = this.entries.get(new Lookup(host));
			return entry != null && entry.pointer != 0;
		}
	}

	/**
	 * Whether {@code host}'s span has been offered through here before and not written
	 * since -- and if not, remembers that it has now. The accept-on-second-sight rule of
	 * the matrix-by-vector product ({@code CudaGemm.gemv}, {@code MetalGemm.gemvF}): a
	 * GEMV over a matrix that is not resident loses to the CPU, so the first offer of a
	 * matrix declines and leaves this mark, the second uploads it, and a matrix written
	 * in between -- which drops the mark exactly as it drops a copy -- is never uploaded
	 * at all. The mark is an {@link Entry} with no buffer, so it shares the weak key, the
	 * write invalidation and the LRU with the copies, and costs nothing on the device. A
	 * copy the array has at a DIFFERENT span is let go of (a dirty one as a
	 * {@link Flush}, which the caller performs at once).
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
			drop(host, entry);
		}
		this.entries.put(new Key(host, this.collected), new Entry(0, offset, bytes, false));
		forget(this.recentlyDropped);
		this.occupied = true;
		return false;
	}

	/**
	 * Forgets one entry's device memory: a clean copy's buffer goes onto the pending list
	 * and out of the byte count; a dirty one whose array is still reachable goes onto the
	 * flush list instead, for the device to download first; a mark holds neither.
	 */
	private void drop(@Nullable Object host, Entry entry) {
		if (entry.pointer == 0) {
			return;
		}
		this.bytes -= entry.bytes;
		if (entry.dirty) {
			this.dirtyCount--;
			if (host != null) {
				this.flushes.add(new Flush(host, entry.pointer, entry.offset, entry.bytes));
				return;
			}
		}
		this.pending.add(entry.pointer);
	}

	/**
	 * Records that {@code pointer} now holds a copy of {@code host}'s span, replacing any
	 * entry the array had, and evicts least-recently-used entries while the cache is over
	 * its budget -- clean ones first, and a dirty one only when no clean one is left, as
	 * a flush rather than a drop. A previous entry at the SAME span is superseded
	 * outright, dirty or not: the new buffer holds the whole span. One at a different
	 * span is flushed if dirty. Replaced and evicted pointers go onto the pending list.
	 * @param host the host array
	 * @param offset the first byte of the span
	 * @param bytes the length of the span
	 * @param pointer the device buffer, which the cache owns from now on
	 * @param dirty whether the device now holds bytes the host array does not
	 */
	synchronized void put(Object host, long offset, long bytes, long pointer, boolean dirty) {
		expunge();
		Entry previous = this.entries.remove(new Lookup(host));
		if (previous != null) {
			if (previous.dirty && previous.offset == offset && previous.bytes == bytes) {
				previous.dirty = false;
				this.dirtyCount--;
			}
			drop(host, previous);
		}
		this.entries.put(new Key(host, this.collected), new Entry(pointer, offset, bytes, dirty));
		this.bytes += bytes;
		forget(this.recentlyDropped);
		if (dirty) {
			this.dirtyCount++;
			forget(this.recentlyClean);
		}
		evictOverBudget(new long[] { pointer });
		this.occupied = !this.entries.isEmpty();
	}

	/**
	 * Marks the resident copy of {@code host}'s span as the authoritative one -- a device
	 * member has just written into it in place -- or does nothing when no such copy is
	 * resident.
	 * @param host the host array
	 * @param offset the first byte of the span
	 * @param bytes the length of the span
	 */
	synchronized void markDirty(Object host, long offset, long bytes) {
		Entry entry = this.entries.get(new Lookup(host));
		if (entry != null && entry.pointer != 0 && entry.offset == offset && entry.bytes == bytes && !entry.dirty) {
			entry.dirty = true;
			this.dirtyCount++;
			forget(this.recentlyClean);
		}
	}

	/**
	 * The materialization claim: if {@code host} has a DIRTY copy, marks it clean and
	 * answers the flush the caller must perform at once (the download into the host
	 * array); answers {@code null} when the array is clean, absent, or was the last one
	 * answered for. The mark is made before the download so that a reader on the same
	 * thread sees a consistent state; the device keeps the entry, so the array stays
	 * resident for the next member.
	 * @param host the host array about to be read
	 * @return the download to perform, or {@code null}
	 */
	@Nullable Flush claimDirty(Object host) {
		if (this.dirtyCount == 0 || recent(this.recentlyClean, this.cleanCursor, host)) {
			return null;
		}
		synchronized (this) {
			Entry entry = this.entries.get(new Lookup(host));
			remember(this.recentlyClean, false, host);
			if (entry == null || !entry.dirty) {
				return null;
			}
			entry.dirty = false;
			this.dirtyCount--;
			return new Flush(host, entry.pointer, entry.offset, entry.bytes);
		}
	}

	/**
	 * The host array was written (or is about to be): its resident copy, if any, is stale
	 * and is dropped. The buffer is not freed here -- see the class comment -- but
	 * queued. The caller has {@linkplain #claimDirty materialized} the array first, so
	 * the entry is clean by the time it is dropped; a dirty one that reaches here anyway
	 * (a caller that did not) is flushed rather than lost.
	 * @param host the host array that was written
	 */
	void written(Object host) {
		if (!this.occupied || recent(this.recentlyDropped, this.droppedCursor, host)) {
			return;
		}
		synchronized (this) {
			Entry entry = this.entries.remove(new Lookup(host));
			if (entry != null) {
				drop(host, entry);
				this.occupied = !this.entries.isEmpty();
			}
			remember(this.recentlyDropped, true, host);
		}
	}

	/**
	 * Drops every entry. The pointers are queued (dirty ones as flushes), not freed; the
	 * caller drains them.
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
			Map.Entry<Object, Entry> slot = each.next();
			Entry entry = slot.getValue();
			boolean kept = false;
			for (long pointer : keep) {
				kept |= pointer != 0 && pointer == entry.pointer;
			}
			if (!kept) {
				each.remove();
				drop(((Key) slot.getKey()).get(), entry);
			}
		}
		this.occupied = !this.entries.isEmpty();
	}

	/**
	 * The LRU: while over budget, drop the least recently used CLEAN entry; when none is
	 * left, the least recently used dirty one, as a flush. Entries in {@code keep} are
	 * the call's own and are never evicted.
	 */
	private void evictOverBudget(long[] keep) {
		boolean cleanLeft = true;
		while (this.bytes > this.budget) {
			Map.Entry<Object, Entry> victim = null;
			for (Map.Entry<Object, Entry> slot : this.entries.entrySet()) {
				Entry entry = slot.getValue();
				boolean kept = false;
				for (long pointer : keep) {
					kept |= pointer != 0 && pointer == entry.pointer;
				}
				if (kept || entry.pointer == 0) {
					continue;
				}
				if (cleanLeft && entry.dirty) {
					continue;
				}
				victim = slot;
				break;
			}
			if (victim == null) {
				if (cleanLeft) {
					cleanLeft = false;
					continue;
				}
				return;
			}
			this.entries.remove(victim.getKey());
			drop(((Key) victim.getKey()).get(), victim.getValue());
		}
	}

	/**
	 * Hands over every pointer dropped, replaced, evicted or orphaned by a collected
	 * array since the last drain, for the caller to free, and forgets them. Dirty copies
	 * are not here -- see {@link #flushes()}.
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
	 * Hands over every dirty copy let go of since the last call, for the caller to
	 * download into its host array and THEN free, and forgets them. The device performs
	 * these immediately after any call that can produce one, never later: between the
	 * drop and the download the host array has no entry, and a reader in that window
	 * would see nothing to materialize.
	 * @return the flushes to perform, possibly empty
	 */
	synchronized Flush[] flushes() {
		if (this.flushes.isEmpty()) {
			return new Flush[0];
		}
		Flush[] out = this.flushes.toArray(new Flush[0]);
		this.flushes.clear();
		return out;
	}

	/**
	 * Queues one buffer for the next {@link #drain}: the owner has performed a
	 * {@link Flush} and is done with the pointer.
	 * @param pointer the device buffer to free at the next safe moment
	 */
	synchronized void release(long pointer) {
		this.pending.add(pointer);
	}

	/**
	 * Marks EVERY dirty copy clean and answers the flushes to perform for each whose
	 * array is still reachable -- the way lazy results are switched off with dirty copies
	 * in play. The entries stay resident.
	 * @return the downloads to perform, possibly empty
	 */
	synchronized Flush[] claimAllDirty() {
		List<Flush> out = new ArrayList<>();
		for (Map.Entry<Object, Entry> slot : this.entries.entrySet()) {
			Entry entry = slot.getValue();
			if (entry.dirty) {
				entry.dirty = false;
				this.dirtyCount--;
				Object host = ((Key) slot.getKey()).get();
				if (host != null) {
					out.add(new Flush(host, entry.pointer, entry.offset, entry.bytes));
				}
			}
		}
		forget(this.recentlyClean);
		return out.toArray(new Flush[0]);
	}

	/**
	 * Moves the entries whose arrays the collector has reclaimed onto the pending list. A
	 * collected key is removed by its own identity, which is the one thing its
	 * {@code equals} still answers; its bytes, dirty or not, are unreadable now.
	 */
	private void expunge() {
		Object key;
		boolean any = false;
		while ((key = this.collected.poll()) != null) {
			Entry entry = this.entries.remove(key);
			if (entry != null) {
				drop(null, entry);
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

	/** How many resident copies are dirty; for the tests. */
	int dirtyCount() {
		return this.dirtyCount;
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
