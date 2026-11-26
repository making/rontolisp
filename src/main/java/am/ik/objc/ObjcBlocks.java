package am.ik.objc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import am.ik.objc.TypeEncoding.Type;
import org.jspecify.annotations.Nullable;

/**
 * Blocks whose bodies are host-language functions ({@code objc:make-objc-block},
 * .kb/objc.md, "Blocks").
 *
 * <p>
 * A block is the Blocks ABI's literal in native memory:
 * {@code isa = &_NSConcreteStackBlock}, the flags {@code BLOCK_HAS_COPY_DISPOSE} and
 * {@code BLOCK_HAS_SIGNATURE}, the invoke pointer, a descriptor, and one field of this
 * binding's own after them -- an ID the body is registered under. The ID travels INSIDE
 * the literal because {@code _Block_copy} copies the literal to the heap whenever a
 * callee keeps the block (every asynchronous API does), and the copy must still find the
 * body; an address key would not survive the copy. IDs are never reused, so a block
 * called after its body is gone is reported, never routed to another body.
 *
 * <p>
 * A STACK block rather than a global one: {@code _Block_copy} of a global block answers
 * the same pointer, so the storage would have to outlive every holder; a stack block's
 * copy lands in memory libclosure owns and frees, and {@code _Block_release} of our own
 * storage is a no-op ({@code BLOCK_NEEDS_FREE} is clear). The copy and dispose helpers
 * count the body's HOLDERS -- the Lisp value that made the block, plus every live copy --
 * so {@link #free} releases only the Lisp value's hold, and the body stays registered
 * until libclosure disposes of the last copy.
 *
 * <p>
 * The invoke function is one FFM upcall stub PER SHAPE (the block's
 * {@link FunctionDescriptor}), all landing in {@link #dispatch}, which finds the body by
 * the ID and converts by the block's own encoding -- two blocks of one shape may differ
 * in what they convert (an object and a pointer are both addresses). A block arrives on
 * whichever thread calls it, a libdispatch worker included: the JVM attaches the thread,
 * and the body runs there. A body that throws never unwinds into the native frame: the
 * failure goes to {@link ObjcMethods#onError the error sink} and the block answers its
 * zero value -- except a {@link ProcessExit}, which {@link ObjcMethods#fail} ends the
 * process for, in place. Under {@code java} every shape is served; a native image serves
 * the shapes under {@code foreign.upcalls} and refuses any other when the block is made.
 */
public final class ObjcBlocks {

	/** The body of a block: the raw arguments after the block itself, the raw answer. */
	@FunctionalInterface
	public interface Body {

		/**
		 * @param args the raw arguments ({@link ObjcMethods}' protocol)
		 * @return the raw answer ({@code null} for void)
		 */
		@Nullable Object invoke(@Nullable Object[] args);

	}

	/** A registered block: its encoding, body and holders. */
	private record Entry(ObjcRuntime runtime, TypeEncoding encoding, Body body, AtomicInteger holders) {
	}

	private static final long INVOKE_OFFSET = 16;

	private static final long ID_OFFSET = 32;

	/** isa, flags + reserved, invoke, descriptor, id. */
	private static final long LITERAL_SIZE = 40;

	private static final int BLOCK_HAS_COPY_DISPOSE = 1 << 25;

	private static final int BLOCK_HAS_SIGNATURE = 1 << 30;

	private static final Map<Long, Entry> LIVE = new ConcurrentHashMap<>();

	private static final AtomicLong IDS = new AtomicLong(1);

	/** One invoke stub per shape, alive for the process. */
	private static final Map<FunctionDescriptor, MemorySegment> INVOKES = new ConcurrentHashMap<>();

	/** One descriptor per signature, alive for the process. */
	private static final Map<String, MemorySegment> DESCRIPTORS = new ConcurrentHashMap<>();

	private static final Set<FunctionDescriptor> UPCALL_SIGNATURES = new LinkedHashSet<>();

	private static volatile @Nullable MemorySegment copyHelper;

	private static volatile @Nullable MemorySegment disposeHelper;

	private ObjcBlocks() {
	}

	/**
	 * The upcall shapes made so far -- the helpers' and the invoke stubs' -- for the
	 * native-image registration test.
	 * @return the shapes
	 */
	public static Set<FunctionDescriptor> upcallSignatures() {
		synchronized (UPCALL_SIGNATURES) {
			return Set.copyOf(UPCALL_SIGNATURES);
		}
	}

	/**
	 * The upcall shape a block of this encoding is invoked through.
	 * @param types the block's encoding, spelled for the call (the block itself as
	 * {@code ^v})
	 * @return the shape
	 */
	public static FunctionDescriptor shape(String types) {
		return TypeEncoding.parse(types).descriptor();
	}

	/**
	 * Makes a block.
	 * @param runtime the binding
	 * @param types the block's encoding for the call: the result, the block itself (a
	 * pointer, {@code ^v}), then the arguments
	 * @param signature the encoding the descriptor carries for {@code _Block_signature},
	 * the block itself spelled {@code @?}
	 * @param body the body
	 * @return the literal's address
	 * @throws ObjcException when the encoding names a type no block can take, or a native
	 * image has no stub for the shape
	 */
	public static long make(ObjcRuntime runtime, String types, String signature, Body body) {
		TypeEncoding encoding = runtime.parsed(types);
		List<Type> params = encoding.argumentTypes();
		if (params.isEmpty() || !params.getFirst().kind().isAddress()) {
			throw new ObjcException("a block's encoding starts with the block itself, got '" + types + "'");
		}
		MemorySegment invoke = invokeStub(encoding);
		MemorySegment descriptor = descriptor(signature);
		long id = IDS.getAndIncrement();
		// C memory, freed by free(): a native image has no shared arena, and a block may
		// be
		// freed from any thread.
		MemorySegment literal = runtime.allocateMemory(LITERAL_SIZE);
		literal.set(ValueLayout.JAVA_LONG, 0, runtime.stackBlockIsa());
		literal.set(ValueLayout.JAVA_INT, 8, BLOCK_HAS_COPY_DISPOSE | BLOCK_HAS_SIGNATURE);
		literal.set(ValueLayout.JAVA_INT, 12, 0);
		literal.set(ValueLayout.ADDRESS, INVOKE_OFFSET, invoke);
		literal.set(ValueLayout.ADDRESS, 24, descriptor);
		literal.set(ValueLayout.JAVA_LONG, ID_OFFSET, id);
		LIVE.put(id, new Entry(runtime, encoding, body, new AtomicInteger(1)));
		return literal.address();
	}

	/**
	 * Gives up the hold the maker of a block has on its body and frees the literal. The
	 * body stays registered while a copy libclosure made is alive.
	 * @param address the literal's address, as {@link #make} answered it
	 */
	public static void free(long address) {
		long id = MemorySegment.ofAddress(address).reinterpret(LITERAL_SIZE).get(ValueLayout.JAVA_LONG, ID_OFFSET);
		Entry entry = LIVE.get(id);
		if (entry == null) {
			return;
		}
		release(id, entry);
		entry.runtime().freeMemory(address);
	}

	private static void release(long id, Entry entry) {
		if (entry.holders().decrementAndGet() <= 0) {
			LIVE.remove(id, entry);
		}
	}

	private static MemorySegment invokeStub(TypeEncoding encoding) {
		FunctionDescriptor descriptor = encoding.descriptor();
		return INVOKES.computeIfAbsent(descriptor, d -> {
			// Bound to the first encoding of the shape: only its result's CARRIER is read
			// (for the zero a failed block answers), and that is the shape's.
			MethodHandle target = MethodHandles.insertArguments(dispatchHandle(), 0, encoding)
				.asCollector(Object[].class, d.argumentLayouts().size())
				.asType(d.toMethodType());
			synchronized (UPCALL_SIGNATURES) {
				UPCALL_SIGNATURES.add(d);
			}
			return ObjcRuntime.upcall(target, d, "a block of " + TypeEncoding.spelling(d));
		});
	}

	private static MemorySegment descriptor(String signature) {
		return DESCRIPTORS.computeIfAbsent(signature, s -> {
			MemorySegment[] helpers = helpers();
			Arena global = Arena.global();
			// Block_descriptor_1 { reserved, size }, _2 { copy, dispose } (present:
			// BLOCK_HAS_COPY_DISPOSE), _3 { signature, layout } (BLOCK_HAS_SIGNATURE).
			// _Block_signature finds the signature by the flags, so all six fields are
			// laid out whatever a reader looks at.
			MemorySegment d = global.allocate(48, 8);
			d.set(ValueLayout.JAVA_LONG, 0, 0L);
			d.set(ValueLayout.JAVA_LONG, 8, LITERAL_SIZE);
			d.set(ValueLayout.ADDRESS, 16, helpers[0]);
			d.set(ValueLayout.ADDRESS, 24, helpers[1]);
			d.set(ValueLayout.ADDRESS, 32, global.allocateFrom(s));
			d.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL);
			return d;
		});
	}

	private static MemorySegment[] helpers() {
		MemorySegment copy = copyHelper;
		MemorySegment dispose = disposeHelper;
		if (copy != null && dispose != null) {
			return new MemorySegment[] { copy, dispose };
		}
		synchronized (ObjcBlocks.class) {
			if (copyHelper == null || disposeHelper == null) {
				try {
					// CONSTANT lookups, which a native image folds into direct handles.
					MethodHandles.Lookup lookup = MethodHandles.lookup();
					FunctionDescriptor copyShape = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
					FunctionDescriptor disposeShape = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
					MethodHandle copied = lookup.findStatic(ObjcBlocks.class, "copied",
							MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
					MethodHandle disposed = lookup.findStatic(ObjcBlocks.class, "disposed",
							MethodType.methodType(void.class, MemorySegment.class));
					synchronized (UPCALL_SIGNATURES) {
						UPCALL_SIGNATURES.add(copyShape);
						UPCALL_SIGNATURES.add(disposeShape);
					}
					copyHelper = ObjcRuntime.upcall(copied, copyShape, "a block's copy helper");
					disposeHelper = ObjcRuntime.upcall(disposed, disposeShape, "a block's dispose helper");
				}
				catch (ReflectiveOperationException ex) {
					throw new ObjcException("the block helpers cannot be bound", ex);
				}
			}
			return new MemorySegment[] { copyHelper, disposeHelper };
		}
	}

	private static volatile @Nullable MethodHandle dispatchHandle;

	private static MethodHandle dispatchHandle() {
		MethodHandle handle = dispatchHandle;
		if (handle == null) {
			try {
				handle = MethodHandles.lookup()
					.findStatic(ObjcBlocks.class, "dispatch",
							MethodType.methodType(Object.class, Object.class, Object[].class));
			}
			catch (ReflectiveOperationException ex) {
				throw new ObjcException("the block dispatcher cannot be bound", ex);
			}
			dispatchHandle = handle;
		}
		return handle;
	}

	private static long idOf(MemorySegment block) {
		return block.reinterpret(LITERAL_SIZE).get(ValueLayout.JAVA_LONG, ID_OFFSET);
	}

	/**
	 * libclosure copied a block to the heap: the copy holds the body too. Public because
	 * an upcall needs a lookup-visible method; never call it.
	 * @param destination the copy
	 * @param source the block copied
	 */
	public static void copied(MemorySegment destination, MemorySegment source) {
		try {
			Entry entry = LIVE.get(idOf(destination));
			if (entry != null) {
				entry.holders().incrementAndGet();
			}
		}
		catch (Throwable ex) {
			ObjcMethods.fail(ex);
		}
	}

	/**
	 * libclosure disposed of a copy. Public because an upcall needs a lookup-visible
	 * method; never call it.
	 * @param block the copy being destroyed
	 */
	public static void disposed(MemorySegment block) {
		try {
			long id = idOf(block);
			Entry entry = LIVE.get(id);
			if (entry != null) {
				release(id, entry);
			}
		}
		catch (Throwable ex) {
			ObjcMethods.fail(ex);
		}
	}

	/**
	 * Every block's invoke lands here. Public because an upcall needs a lookup-visible
	 * method; never call it.
	 * @param shape the encoding of the first block of this shape
	 * @param args the native arguments, the block first
	 * @return the native result, boxed as its carrier
	 */
	public static @Nullable Object dispatch(Object shape, Object[] args) {
		Type ret = ((TypeEncoding) shape).returnType();
		try {
			long id = idOf(MemorySegment.ofAddress(((MemorySegment) args[0]).address()));
			Entry entry = LIVE.get(id);
			if (entry == null) {
				throw new ObjcException("a block was called after its last holder let go of it (block " + id + ")");
			}
			List<Type> params = entry.encoding().argumentTypes();
			@Nullable Object[] raw = new @Nullable Object[args.length - 1];
			for (int i = 1; i < args.length; i++) {
				raw[i - 1] = ObjcMethods.toRaw(entry.runtime(), params.get(i), args[i]);
			}
			Object answer = entry.body().invoke(raw);
			// A block's object answer is the caller's at +0, as a method's outside the
			// owning families.
			return ObjcMethods.toNative(entry.runtime(), ObjcMethods.RETAIN_RESULT | ObjcMethods.AUTORELEASE_RESULT,
					entry.encoding().returnType(), answer);
		}
		catch (Throwable ex) {
			ObjcMethods.fail(ex);
			return ObjcMethods.zero(ret);
		}
	}

}
