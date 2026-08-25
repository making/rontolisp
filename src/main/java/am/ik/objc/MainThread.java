package am.ik.objc;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

/**
 * The process's first thread, and the pump that runs work on it.
 *
 * <p>
 * AppKit belongs to thread 0 -- the thread the kernel started the process on, the one
 * {@code pthread_main_np()} answers 1 for -- and nothing else may touch a window. The
 * caller's thread is never it: under {@code java -jar} the launcher parks thread 0 in a
 * {@code CFRunLoop} and runs {@code main} on a second thread, and in a native image
 * {@link #handOverRequired() the binary does the same by hand}. So every entry point here
 * is a hop: {@link #sync} hands a body to the main dispatch queue with
 * {@code dispatch_sync_f} and waits, and the run loop on thread 0 drains the queue.
 *
 * <p>
 * <strong>The re-entrancy rule.</strong> A callback (a button's action) already runs on
 * thread 0, and the code it runs calls back into the GUI. {@code dispatch_sync} to the
 * queue you are already draining is a deadlock the spike hit on its first click, so
 * {@link #sync} tests {@code pthread_main_np()} first and runs the body inline when it is
 * already there. Every caller goes through {@link #sync}; none may dispatch on its own.
 *
 * <p>
 * The bodies cross to thread 0 through ONE upcall stub, {@link #trampoline}, whose
 * context pointer is a ticket into {@link #SLOTS}; the shape {@code void(void*)} is the
 * only upcall this class needs, so it is the only one a native image must register for
 * it. Nothing here is Objective-C: libSystem (libdispatch, pthread) and CoreFoundation
 * are the whole binding, and it is opened separately from {@link ObjcRuntime} so that the
 * hand-over check at a native binary's startup costs a {@code dlopen} of two libraries
 * that are already in the shared cache, not a load of AppKit.
 */
public final class MainThread {

	private static final Linker LINKER = Linker.nativeLinker();

	private static final AddressLayout P = ValueLayout.ADDRESS;

	static final String LIB_SYSTEM = "/usr/lib/libSystem.B.dylib";

	static final String LIB_CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

	/** The bodies in flight, by ticket; a ticket is used exactly once. */
	private static final Map<Long, Slot> SLOTS = new ConcurrentHashMap<>();

	private static final AtomicLong TICKETS = new AtomicLong(1);

	private static final Object OPEN_LOCK = new Object();

	private static @Nullable MainThread instance;

	private static @Nullable String unavailableReason;

	private final MethodHandle pthreadMainNp;

	private final MethodHandle dispatchSyncF;

	private final MethodHandle dispatchAsyncF;

	private final MethodHandle cfRunLoopRunInMode;

	private final MethodHandle cfRunLoopGetCurrent;

	private final MethodHandle cfRunLoopSourceCreate;

	private final MethodHandle cfRunLoopAddSource;

	private final MemorySegment defaultMode;

	private final MemorySegment mainQueue;

	private final MemorySegment trampolineStub;

	private final Set<FunctionDescriptor> signatures = new LinkedHashSet<>();

	private final Set<FunctionDescriptor> upcallSignatures = new LinkedHashSet<>();

	/**
	 * Binds libSystem and CoreFoundation. Package-private so the native-image
	 * registration test can construct one against a lookup that finds every name.
	 * @param libSystem the libSystem lookup
	 * @param coreFoundation the CoreFoundation lookup
	 */
	MainThread(SymbolLookup libSystem, SymbolLookup coreFoundation) {
		this.pthreadMainNp = handle(libSystem, "pthread_main_np", FunctionDescriptor.of(ValueLayout.JAVA_INT));
		this.dispatchSyncF = handle(libSystem, "dispatch_sync_f", FunctionDescriptor.ofVoid(P, P, P));
		this.dispatchAsyncF = handle(libSystem, "dispatch_async_f", FunctionDescriptor.ofVoid(P, P, P));
		this.cfRunLoopRunInMode = handle(coreFoundation, "CFRunLoopRunInMode",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_BOOLEAN));
		this.cfRunLoopGetCurrent = handle(coreFoundation, "CFRunLoopGetCurrent", FunctionDescriptor.of(P));
		this.cfRunLoopSourceCreate = handle(coreFoundation, "CFRunLoopSourceCreate",
				FunctionDescriptor.of(P, P, ValueLayout.JAVA_LONG, P));
		this.cfRunLoopAddSource = handle(coreFoundation, "CFRunLoopAddSource", FunctionDescriptor.ofVoid(P, P, P));
		// A data symbol: the address of the CFStringRef global, whose value is the mode.
		this.defaultMode = coreFoundation.find("kCFRunLoopDefaultMode")
			.orElseThrow(() -> new ObjcException("kCFRunLoopDefaultMode is missing"));
		this.mainQueue = libSystem.find("_dispatch_main_q")
			.orElseThrow(() -> new ObjcException("_dispatch_main_q is missing"));
		try {
			// A CONSTANT lookup: a native image folds it into a direct handle, where a
			// name arriving in a variable needs reflection metadata the image does not
			// have (the spike's NoSuchMethodException).
			MethodHandle target = MethodHandles.lookup()
				.findStatic(MainThread.class, "trampoline", MethodType.methodType(void.class, MemorySegment.class));
			FunctionDescriptor shape = FunctionDescriptor.ofVoid(P);
			this.upcallSignatures.add(shape);
			this.trampolineStub = LINKER.upcallStub(target, shape, Arena.global());
		}
		catch (ReflectiveOperationException ex) {
			throw new ObjcException("the main-thread trampoline cannot be bound", ex);
		}
	}

	private MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		MemorySegment symbol = lookup.find(name).orElseThrow(() -> new ObjcException(name + " is missing"));
		this.signatures.add(descriptor);
		try {
			return LINKER.downcallHandle(symbol, descriptor);
		}
		catch (Throwable ex) {
			throw new ObjcException(
					name + ": the shape " + TypeEncoding.spelling(descriptor)
							+ " has no foreign-call stub (a native image registers them in reachability-metadata.json)",
					ex);
		}
	}

	/**
	 * The downcall shapes this binding asked the linker for, for the native-image
	 * registration test.
	 * @return the shapes
	 */
	Set<FunctionDescriptor> signatures() {
		return Set.copyOf(this.signatures);
	}

	/**
	 * The upcall shapes this binding built stubs for.
	 * @return the shapes
	 */
	Set<FunctionDescriptor> upcallSignatures() {
		return Set.copyOf(this.upcallSignatures);
	}

	/**
	 * The process-wide pump, opened on first use.
	 * @return the pump
	 * @throws ObjcException when this machine has no libSystem/CoreFoundation -- any
	 * platform but macOS, or a JVM that forbids native access
	 */
	public static MainThread get() {
		MainThread opened = open();
		if (opened == null) {
			throw new ObjcException(unavailableReason());
		}
		return opened;
	}

	/**
	 * Opens the pump, or answers {@code null} when the libraries are not there.
	 * @return the pump, or {@code null} off macOS / without native access
	 */
	public static @Nullable MainThread open() {
		synchronized (OPEN_LOCK) {
			if (instance != null || unavailableReason != null) {
				return instance;
			}
			try {
				Arena arena = Arena.global();
				instance = new MainThread(SymbolLookup.libraryLookup(LIB_SYSTEM, arena),
						SymbolLookup.libraryLookup(LIB_CORE_FOUNDATION, arena));
			}
			catch (Throwable ex) {
				unavailableReason = describeFailure(ex);
			}
			return instance;
		}
	}

	/**
	 * Why {@link #open()} answered {@code null}, in one line.
	 * @return the reason, or an empty string when the pump opened
	 */
	public static String unavailableReason() {
		synchronized (OPEN_LOCK) {
			return unavailableReason == null ? "" : unavailableReason;
		}
	}

	private static String describeFailure(Throwable ex) {
		String os = System.getProperty("os.name", "");
		if (!os.toLowerCase(java.util.Locale.ROOT).contains("mac")) {
			return "Objective-C needs macOS, and this is " + os;
		}
		if (ex instanceof IllegalCallerException) {
			return "native access is denied to this module: run with --enable-native-access=ALL-UNNAMED";
		}
		return "libSystem/CoreFoundation cannot be bound: " + ex;
	}

	/**
	 * Whether the calling thread is the process's first thread.
	 * @return {@code true} on thread 0
	 */
	public boolean isMainThread() {
		try {
			return (int) this.pthreadMainNp.invokeExact() != 0;
		}
		catch (Throwable ex) {
			throw new ObjcException("pthread_main_np failed", ex);
		}
	}

	/**
	 * Whether this process must hand thread 0 over by itself: it is a native image on
	 * macOS whose {@code main} IS thread 0, so nothing drains the main queue until the
	 * caller moves its own work off and calls {@link #runLoop()}. Cheap enough to ask at
	 * every startup (libSystem and CoreFoundation, no AppKit), and never throws.
	 * @return {@code true} when the caller must spawn its work and park thread 0
	 */
	public static boolean handOverRequired() {
		if (System.getProperty("org.graalvm.nativeimage.imagecode") == null
				|| !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
			return false;
		}
		try {
			MainThread pump = open();
			return pump != null && pump.isMainThread();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * Parks the calling thread -- which must be thread 0 -- in the main run loop, which
	 * pumps AppKit's events and the main dispatch queue. Never returns: the process ends
	 * through {@code System.exit} from the thread that took over the work.
	 *
	 * <p>
	 * This is the java launcher's {@code ParkEventLoop}, not a bare {@code CFRunLoopRun}:
	 * a run loop with no source of its own reports itself finished, and AppKit stops the
	 * loop from inside some of its own paths (the first cut parked in
	 * {@code CFRunLoopRun} and lost thread 0 after the first click -- the loop had
	 * returned, {@code main} had returned, and the binary sat waiting for its worker with
	 * a beach-balled window). So a no-op source keeps the default mode non-empty, and a
	 * stop only re-enters the loop.
	 */
	public void runLoop() {
		try {
			MemorySegment mode = this.defaultMode.reinterpret(P.byteSize()).get(P, 0);
			MemorySegment runLoop = (MemorySegment) this.cfRunLoopGetCurrent.invokeExact();
			// CFRunLoopSourceContext: version, info, retain, release, copyDescription,
			// equal, hash, schedule, cancel, perform -- a CFIndex and nine pointers. The
			// perform callback is the trampoline with a ticket nobody issued, a no-op.
			MemorySegment context = Arena.global().allocate(SOURCE_CONTEXT);
			context.set(ValueLayout.JAVA_LONG, 0, 0L);
			context.set(P, 72, this.trampolineStub);
			MemorySegment source = (MemorySegment) this.cfRunLoopSourceCreate.invokeExact(MemorySegment.NULL, 0L,
					context);
			this.cfRunLoopAddSource.invokeExact(runLoop, source, mode);
			while (true) {
				int result = (int) this.cfRunLoopRunInMode.invokeExact(mode, 1.0e20, false);
				trace("run loop returned " + result + "; re-entering");
			}
		}
		catch (Throwable ex) {
			throw new ObjcException("the main run loop failed", ex);
		}
	}

	private static final java.lang.foreign.StructLayout SOURCE_CONTEXT = java.lang.foreign.MemoryLayout
		.structLayout(ValueLayout.JAVA_LONG, P, P, P, P, P, P, P, P, P);

	/** {@code RONTOLISP_OBJC_TRACE=1} prints every hop and every run-loop return. */
	private static final boolean TRACE = System.getenv("RONTOLISP_OBJC_TRACE") != null;

	private static void trace(String message) {
		if (TRACE) {
			System.err.println("[objc " + Thread.currentThread().getName() + "] " + message);
		}
	}

	/**
	 * Runs a body on thread 0 and waits for its value: inline when the caller is already
	 * there (the re-entrancy rule), otherwise through {@code dispatch_sync_f}. An
	 * exception the body throws is rethrown on the caller's thread as it was.
	 * @param <T> the body's value type
	 * @param body the work
	 * @return the body's value
	 */
	public <T> @Nullable T sync(Callable<T> body) {
		if (isMainThread()) {
			trace("sync inline on thread 0");
			return run(body);
		}
		long ticket = TICKETS.getAndIncrement();
		Slot slot = new Slot(body);
		SLOTS.put(ticket, slot);
		trace("sync ticket " + ticket + " -> main");
		try {
			this.dispatchSyncF.invokeExact(this.mainQueue, MemorySegment.ofAddress(ticket), this.trampolineStub);
		}
		catch (Throwable ex) {
			SLOTS.remove(ticket);
			throw new ObjcException("dispatch_sync_f failed", ex);
		}
		trace("sync ticket " + ticket + " done");
		if (slot.failure != null) {
			throw rethrow(slot.failure);
		}
		@SuppressWarnings("unchecked")
		T value = (T) slot.value;
		return value;
	}

	/**
	 * Queues a body for thread 0 and returns at once. Used for the work that must not
	 * wait -- releasing an object whose last Lisp reference was collected.
	 * @param body the work
	 */
	public void async(Runnable body) {
		long ticket = TICKETS.getAndIncrement();
		SLOTS.put(ticket, new Slot(() -> {
			body.run();
			return null;
		}));
		trace("async ticket " + ticket + " -> main");
		try {
			this.dispatchAsyncF.invokeExact(this.mainQueue, MemorySegment.ofAddress(ticket), this.trampolineStub);
		}
		catch (Throwable ex) {
			SLOTS.remove(ticket);
			throw new ObjcException("dispatch_async_f failed", ex);
		}
	}

	private static <T> @Nullable T run(Callable<T> body) {
		try {
			return body.call();
		}
		catch (Exception ex) {
			throw rethrow(ex);
		}
	}

	private static RuntimeException rethrow(Throwable failure) {
		if (failure instanceof RuntimeException runtime) {
			return runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
		return new ObjcException(failure.toString(), failure);
	}

	/**
	 * The upcall target: runs the body the ticket names, on whichever thread drains the
	 * main queue. Public because an upcall stub needs a lookup-visible method; never call
	 * it directly.
	 * @param ticket the ticket handed to {@code dispatch_*_f} as the context pointer
	 */
	public static void trampoline(MemorySegment ticket) {
		Slot slot = SLOTS.remove(ticket.address());
		if (slot == null) {
			return;
		}
		trace("running ticket " + ticket.address() + " on thread 0");
		try {
			slot.value = slot.body.call();
		}
		catch (Throwable ex) {
			// Never let anything escape into the native frame above: an exception
			// crossing an upcall boundary takes the whole process down.
			slot.failure = ex;
		}
	}

	private static final class Slot {

		private final Callable<?> body;

		private volatile @Nullable Object value;

		private volatile @Nullable Throwable failure;

		Slot(Callable<?> body) {
			this.body = body;
		}

	}

}
