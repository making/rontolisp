package am.ik.gpu;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
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
 * and every host read of that array has to {@linkplain #claim materialize} it first; the
 * enumeration of those readers, on each interceptor, is in {@code .kb/gpu.md} ("The two
 * seams, and what must report through them") and pinned by a test on each, as the writers
 * are. The device side never drops a dirty entry on its own: every path that removes one
 * -- an eviction, a release, a replacement at a different span -- hands the buffer back
 * to the owning device as a {@link Flush} to DOWNLOAD before it is freed, and the device
 * does so at once, before it returns to its caller. A dirty entry whose host array has
 * been collected holds bytes nobody can read, and is simply freed.
 *
 * <h2>A result's host array may be a STUB, and the storage is then this class's</h2>
 *
 * Since {@code .todo/492} the host array a member is handed for its RESULT may be shorter
 * than the span it stands for -- an array holding only the prefix ahead of the elements
 * (the JVM class output's {@code [rank, dim...]} header; nothing at all on the
 * interpreter), allocated by an interceptor that does not want to pay for a zeroed host
 * array nobody may ever read. Such a stub is recognised structurally -- it is shorter
 * than the span the entry records -- and never written into: its bytes live on the device
 * while the entry is dirty, and in a BACKING array this class allocates the first time
 * the host asks for them ({@link #claim}: the prefix copied from the stub, the elements
 * downloaded by the owner). The backing is held STRONGLY for as long as the stub is
 * reachable (a second weak-keyed map, {@code backings}), answered by every later claim,
 * written through by the host's setters, and uploaded from when the stub is offered again
 * after its device copy was dropped. So a stub is in one of three states and never a
 * fourth: a dirty device copy and no backing; a device copy and a backing; a backing
 * alone. Every path that lets a dirty copy go flushes it into the backing first, and a
 * stub that has neither is a broken invariant the owner throws on rather than uploading
 * zeros. The identity the interceptors key on is the STUB's -- it is the object the
 * program holds -- and the backing is handed out only for the duration of a host read or
 * write; a host rung that would answer its argument back is made to answer the caller's
 * own object ({@code .kb/gpu.md}, "Lazy results, and the result that has no host array").
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
 * read calls {@link Gpu#materialize(Object)} first and reads what it answers. Writing or
 * reading through a path that is not enumerated is a silent wrong answer, which is why
 * both enumerations are pinned by a test on each backend.
 *
 * <h2>The release policy, and the collector it has to wake</h2>
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
 * <p>
 * "Entries die with their arrays" assumed the collector RUNS, and with result stubs it no
 * longer does on its own: a stub is twenty bytes, so a program whose every result is one
 * allocates almost nothing on the heap, the young generation takes minutes to fill, and
 * the stubs a training step has dropped -- with the 25-100 MB device buffers behind them
 * -- stay uncollected, and therefore resident, until the pool reaches its budget;
 * evicting them THEN would flush live-looking dead results into fresh backings, which is
 * the allocation this whole mode exists to avoid, and on a unified-memory machine the
 * pool at its budget is the host's memory too. So the LRU evicts CLEAN copies on its own
 * and, when only dirty ones are left, STOPS and asks for a collection instead
 * ({@link #collectionWanted}): the owner runs {@code System.gc()} -- the precedent is the
 * JDK's own direct buffers, whose off-heap memory is governed by small Java objects the
 * same way and which call the collector when their limit is hit -- drains what the
 * collector released, and only then evicts what is still over budget
 * ({@link #evictOverBudget}), as a flush. A collection is asked for at most once per
 * {@link #COLLECTION_SHARE} of the budget PRODUCED since the last one
 * ({@link #producedSinceCollection}), so a live set that genuinely exceeds the budget
 * does not collect on every call. {@code .kb/gpu.md}, "The collector, and the flags that
 * do and do not help", has the measurement.
 *
 * <h2>Cost on the read and write paths</h2>
 *
 * {@link #written(Object)} and {@link #recentClaim(Object)} are called once per element
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
	 * host storage and only then free -- an evicted, released or replaced entry whose
	 * bytes the host does not yet have. The target is held strongly here so it cannot be
	 * collected between the decision and the download; it is the host array itself, or
	 * the backing this class allocated when the host array is a stub.
	 *
	 * @param target the array the bytes belong in
	 * @param pointer the device buffer
	 * @param offset the first byte of the span in the target
	 * @param bytes the length of the span
	 */
	record Flush(Object target, long pointer, long offset, long bytes) {
	}

	/**
	 * The answer of a materialization {@link #claim}: the array that holds -- or, once
	 * {@code flush} is performed, will hold -- the host array's bytes, and the download
	 * the owner must perform first, if any.
	 *
	 * @param storage the array to read: the host array, or a stub's backing
	 * @param flush the download to perform before reading it, or {@code null}
	 */
	record Claim(Object storage, @Nullable Flush flush) {
	}

	private final ReferenceQueue<Object> collected = new ReferenceQueue<>();

	private final LinkedHashMap<Object, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

	/**
	 * Stub to backing (see the class comment): the same weak identity keys, on the same
	 * queue, so a collected stub takes its backing with it at the next {@link #expunge}.
	 * Separate from {@link #entries}, which is about device memory and is what the LRU
	 * walks; a backing outlives the device copy.
	 */
	private final HashMap<Object, Object> backings = new HashMap<>();

	/** {@code true} while any stub has a backing: the cheap gate for {@link #claim}. */
	private volatile boolean backed;

	private final List<Long> pending = new ArrayList<>();

	private final List<Flush> flushes = new ArrayList<>();

	private long bytes;

	private long budget;

	/**
	 * Bytes recorded by {@link #put} since the owner last collected; see the class
	 * comment.
	 */
	private long producedSinceCollection;

	/**
	 * Set when the LRU found only DIRTY entries left to evict and left them for the owner
	 * to collect first; cleared by {@link #evictOverBudget}.
	 */
	private boolean collectionWanted;

	/**
	 * How much of the budget must have been PUT since the last collection before another
	 * is asked for: an eighth.
	 */
	static final int COLLECTION_SHARE = 8;

	/** How many entries are dirty; the cheap gate for {@link #recentClaim}. */
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
	 * The arrays {@link #claim} recently answered for (materialized, clean already, or
	 * absent), so that an element loop reading them pays the lock once. Any dirty
	 * insertion clears them, because an array may be dirty again.
	 */
	private final @Nullable Recent[] recentlyClean = new @Nullable Recent[RECENT];

	private volatile int cleanCursor;

	/**
	 * One slot of the clean ring: the array and the storage answered for it, as ONE
	 * immutable pair, so that a reader racing the writer sees a slot whose two halves
	 * belong together or a slot it does not match -- never a host with another's storage.
	 */
	private record Recent(Object host, Object storage) {
	}

	/** Whether {@code host} is in the ring; a volatile read per slot, no allocation. */
	private boolean recent(@Nullable Object[] ring, int cursor, Object host) {
		for (int i = 0; i < RECENT; i++) {
			if (ring[i] == host) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds {@code host} to the dropped ring; under the monitor, so the cursor is
	 * consistent.
	 */
	private void rememberDropped(Object host) {
		int cursor = this.droppedCursor;
		this.recentlyDropped[cursor] = host;
		this.droppedCursor = (cursor + 1) % RECENT;
	}

	/**
	 * Adds {@code host} and the storage answered for it to the clean ring; under the
	 * monitor, so the cursor is consistent.
	 */
	private void rememberClean(Object host, Object storage) {
		int cursor = this.cleanCursor;
		this.recentlyClean[cursor] = new Recent(host, storage);
		this.cleanCursor = (cursor + 1) % RECENT;
	}

	/** Empties a ring; under the monitor. */
	private void forget(@Nullable Object[] ring) {
		for (int i = 0; i < RECENT; i++) {
			ring[i] = null;
		}
	}

	/**
	 * The clean ring's storage answer for {@code host}, or {@code null} when absent. The
	 * volatile cursor is read first, which is what orders the slot reads after the
	 * writer's stores.
	 */
	private @Nullable Object recentStorage(Object host) {
		int cursor = this.cleanCursor;
		for (int i = 0; i < RECENT; i++) {
			Recent slot = this.recentlyClean[(cursor + i) % RECENT];
			if (slot != null && slot.host == host) {
				return slot.storage;
			}
		}
		return null;
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
	 * Whether {@code host}'s OWN entry is dirty right now -- the device holds bytes it
	 * does not -- as opposed to {@link #dirtyCount()}, a process-wide tally that also
	 * counts whatever garbage an earlier caller's arrays left in the cache until the
	 * collector reaches them. A test that wants to know about ONE result asks this
	 * instead of diffing the tally around it.
	 * @param host the host array (a result's own storage, most often a stub)
	 * @return {@code true} when {@code host}'s entry is dirty
	 */
	boolean dirty(Object host) {
		if (!this.occupied) {
			return false;
		}
		synchronized (this) {
			Entry entry = this.entries.get(new Lookup(host));
			return entry != null && entry.dirty;
		}
	}

	/**
	 * Whether {@code host} (a stub) holds a backing right now -- the per-handle answer
	 * {@link #backingCount()}'s tally cannot give on its own, for the same reason
	 * {@link #dirty(Object)} exists next to {@link #dirtyCount()}.
	 * @param host the stub
	 * @return {@code true} when a backing array has been allocated for it
	 */
	boolean backed(Object host) {
		if (!this.backed) {
			return false;
		}
		synchronized (this) {
			return this.backings.get(new Lookup(host)) != null;
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
				this.flushes.add(new Flush(storageFor(host, entry.offset, entry.bytes), entry.pointer, entry.offset,
						entry.bytes));
				return;
			}
		}
		this.pending.add(entry.pointer);
	}

	/**
	 * The array that holds {@code host}'s bytes at the span, allocating a stub's backing
	 * if it has none yet: {@code host} itself when it is long enough to hold the span,
	 * else its backing -- a fresh array of the span's full length with {@code host}'s own
	 * prefix (the interceptor's header) copied in, recorded against the stub and kept for
	 * as long as the stub is reachable.
	 * @param host the host array
	 * @param offset the first byte of the span
	 * @param bytes the length of the span
	 * @return the array holding, or about to hold, the span
	 */
	synchronized Object storageFor(Object host, long offset, long bytes) {
		if (!stub(host, offset, bytes)) {
			return host;
		}
		Object backing = this.backings.get(new Lookup(host));
		if (backing == null) {
			backing = allocateBacking(host, offset + bytes);
			this.backings.put(new Key(host, this.collected), backing);
			this.backed = true;
		}
		return backing;
	}

	/**
	 * The array holding {@code host}'s bytes for an UPLOAD from the host: {@code host},
	 * or a stub's backing -- which a stub whose device copy is gone always has (the class
	 * comment's invariant). A stub with neither is a broken invariant, and uploading its
	 * zeros would be a silent wrong answer, so it throws instead.
	 * @param host the host array about to be uploaded
	 * @param offset the first byte of the span
	 * @param bytes the length of the span
	 * @return the array to upload from
	 */
	synchronized Object source(Object host, long offset, long bytes) {
		if (!stub(host, offset, bytes)) {
			return host;
		}
		Object backing = this.backings.get(new Lookup(host));
		if (backing == null) {
			throw new IllegalStateException("a device result's host array has neither a device copy nor storage");
		}
		return backing;
	}

	/**
	 * The array that holds {@code host}'s bytes for a host READ or WRITE that has no span
	 * to hand -- the fast answer once a stub has a backing, and {@code host} itself for
	 * every ordinary array. Under the monitor.
	 */
	private Object storageOf(Object host) {
		Object backing = this.backings.get(new Lookup(host));
		return backing != null ? backing : host;
	}

	/**
	 * The element count {@code host} stands for: its own length, or the end of the span
	 * its entry mirrors, or its backing's length, whichever is largest -- so that a stub
	 * passes the bounds checks at the span it was created with, and an ordinary array at
	 * its own length. A volatile read when nothing is resident or backed.
	 * @param host the host array
	 * @return the extent, in elements
	 */
	long extent(Object host) {
		long own = lengthInBytes(host);
		if (!this.occupied && !this.backed) {
			return own / width(host);
		}
		synchronized (this) {
			Entry entry = this.entries.get(new Lookup(host));
			long bytes = own;
			if (entry != null) {
				bytes = Math.max(bytes, entry.offset + entry.bytes);
			}
			Object backing = this.backings.get(new Lookup(host));
			if (backing != null) {
				bytes = Math.max(bytes, lengthInBytes(backing));
			}
			return bytes / width(host);
		}
	}

	private static int width(Object host) {
		return host instanceof float[] ? Float.BYTES : Double.BYTES;
	}

	/** Whether {@code host} is too short to hold the span -- a stub (class comment). */
	private static boolean stub(Object host, long offset, long bytes) {
		return lengthInBytes(host) < offset + bytes;
	}

	private static long lengthInBytes(Object host) {
		if (host instanceof float[] f) {
			return (long) f.length * Float.BYTES;
		}
		if (host instanceof double[] d) {
			return (long) d.length * Double.BYTES;
		}
		return Long.MAX_VALUE;
	}

	/** A stub's backing: the full span, with the stub's own prefix copied in. */
	private static Object allocateBacking(Object stub, long spanEnd) {
		if (stub instanceof float[] f) {
			float[] backing = new float[Math.toIntExact(spanEnd / Float.BYTES)];
			System.arraycopy(f, 0, backing, 0, f.length);
			return backing;
		}
		double[] d = (double[]) stub;
		double[] backing = new double[Math.toIntExact(spanEnd / Double.BYTES)];
		System.arraycopy(d, 0, backing, 0, d.length);
		return backing;
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
		this.producedSinceCollection += bytes;
		forget(this.recentlyDropped);
		if (dirty) {
			this.dirtyCount++;
			forget(this.recentlyClean);
		}
		evict(new long[] { pointer }, false);
		this.occupied = !this.entries.isEmpty();
	}

	/**
	 * Whether the last {@link #put} left the cache over budget with only DIRTY entries to
	 * evict, for the owner to collect first (class comment) and then
	 * {@link #evictOverBudget}.
	 * @return {@code true} while a collection is wanted
	 */
	synchronized boolean collectionWanted() {
		return this.collectionWanted;
	}

	/**
	 * Whether enough has been produced since the last collection for another to be worth
	 * asking for -- {@link #COLLECTION_SHARE}'s rule.
	 * @return {@code true} when the owner may run the collector now
	 */
	synchronized boolean collectionDue() {
		return this.producedSinceCollection >= Math.max(this.budget / COLLECTION_SHARE, 64L << 20);
	}

	/** The owner has run the collector; the production count starts over. */
	synchronized void collected() {
		this.producedSinceCollection = 0;
	}

	/**
	 * The forced half of the LRU: evicts whatever is still over budget, dirty entries
	 * included -- as flushes -- keeping the call's own buffers. The owner calls it after
	 * a collection and the drain that follows; {@link #put} itself evicts only clean
	 * entries.
	 * @param keep device pointers to leave resident
	 */
	synchronized void evictOverBudget(long[] keep) {
		expunge();
		evict(keep, true);
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
	 * The fast half of the materialization claim: the storage to read for {@code host}
	 * when nothing needs the monitor -- {@code host} itself while nothing is dirty and no
	 * stub is backed, or the ring's answer for an array claimed recently -- and
	 * {@code null} when {@link #claim} must be asked. A volatile read and at most four
	 * identity compares, for the element loops that call it once per element.
	 * @param host the host array about to be read
	 * @return the array to read, or {@code null} when the slow path must decide
	 */
	@Nullable Object recentClaim(Object host) {
		if (this.dirtyCount == 0 && !this.backed) {
			return host;
		}
		return recentStorage(host);
	}

	/**
	 * The materialization claim: the array that holds {@code host}'s bytes ({@code host},
	 * or a stub's backing, allocated now if the stub has none), and -- if {@code host}
	 * has a DIRTY copy, which is marked clean here -- the flush the caller must perform
	 * at once, the download into that storage. The mark is made before the download so
	 * that a reader on the same thread sees a consistent state; the device keeps the
	 * entry, so the array stays resident for the next member. The answer is remembered in
	 * the ring for {@link #recentClaim}.
	 * @param host the host array about to be read
	 * @return the storage and the download to perform first, if any
	 */
	synchronized Claim claim(Object host) {
		Entry entry = this.entries.get(new Lookup(host));
		Object storage;
		Flush flush = null;
		if (entry != null && entry.dirty) {
			entry.dirty = false;
			this.dirtyCount--;
			storage = storageFor(host, entry.offset, entry.bytes);
			flush = new Flush(storage, entry.pointer, entry.offset, entry.bytes);
		}
		else {
			storage = storageOf(host);
		}
		rememberClean(host, storage);
		return new Claim(storage, flush);
	}

	/**
	 * The host array was written (or is about to be): its resident copy, if any, is stale
	 * and is dropped. The buffer is not freed here -- see the class comment -- but
	 * queued. The caller has {@linkplain #claim materialized} the array first, so the
	 * entry is clean by the time it is dropped; a dirty one that reaches here anyway (a
	 * caller that did not) is flushed rather than lost.
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
			rememberDropped(host);
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
	 * left, either stop and ask for a collection ({@code dirtyToo} false) or evict the
	 * least recently used dirty one as a flush ({@code dirtyToo} true). Entries in
	 * {@code keep} are the call's own and are never evicted.
	 */
	private void evict(long[] keep, boolean dirtyToo) {
		boolean cleanLeft = true;
		this.collectionWanted = false;
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
					if (!dirtyToo) {
						this.collectionWanted = true;
						return;
					}
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
					out.add(new Flush(storageFor(host, entry.offset, entry.bytes), entry.pointer, entry.offset,
							entry.bytes));
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
		boolean anyBacking = false;
		while ((key = this.collected.poll()) != null) {
			Entry entry = this.entries.remove(key);
			if (entry != null) {
				drop(null, entry);
				any = true;
			}
			anyBacking |= this.backings.remove(key) != null;
		}
		if (any) {
			this.occupied = !this.entries.isEmpty();
		}
		if (anyBacking) {
			this.backed = !this.backings.isEmpty();
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

	/** How many stubs hold a backing right now; for the tests. */
	synchronized int backingCount() {
		expunge();
		return this.backings.size();
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
