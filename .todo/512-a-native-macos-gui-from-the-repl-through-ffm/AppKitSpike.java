import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

interface Body<T> { T run() throws Throwable; }

/** A static facade over AppKit, callable from the rontolisp REPL through java:static. */
public final class AppKitSpike {
	static final Linker LINKER = Linker.nativeLinker();
	static final AddressLayout P = ValueLayout.ADDRESS;
	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
	static final ValueLayout.OfBoolean B = ValueLayout.JAVA_BOOLEAN;
	static final MemoryLayout CGRECT = MemoryLayout.structLayout(D, D, D, D);
	static final Arena A = Arena.global();
	static SymbolLookup objc, libsys;
	static MethodHandle getClass_, selReg, allocClassPair, addMethod, registerClassPair, syncF, isMain;
	static final Map<String, MemorySegment> sels = new HashMap<>(), classes = new HashMap<>();
	static final Map<String, MethodHandle> sends = new ConcurrentHashMap<>();
	static final ArrayBlockingQueue<Object[]> pending = new ArrayBlockingQueue<>(64);
	static final Map<Long, Runnable> actions = new ConcurrentHashMap<>();
	static final AtomicLong ids = new AtomicLong();
	static MemorySegment targetClass, mainQ, onMainStub;

	static {
		try {
			objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", A);
			libsys = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", A);
			SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", A);
			getClass_ = LINKER.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(P, P));
			selReg = LINKER.downcallHandle(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(P, P));
			allocClassPair = LINKER.downcallHandle(objc.find("objc_allocateClassPair").orElseThrow(), FunctionDescriptor.of(P, P, P, L));
			addMethod = LINKER.downcallHandle(objc.find("class_addMethod").orElseThrow(), FunctionDescriptor.of(B, P, P, P, P));
			registerClassPair = LINKER.downcallHandle(objc.find("objc_registerClassPair").orElseThrow(), FunctionDescriptor.ofVoid(P));
			syncF = LINKER.downcallHandle(libsys.find("dispatch_sync_f").orElseThrow(), FunctionDescriptor.ofVoid(P, P, P));
			isMain = LINKER.downcallHandle(libsys.find("pthread_main_np").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			mainQ = libsys.find("_dispatch_main_q").orElseThrow();
			onMainStub = LINKER.upcallStub(MethodHandles.lookup().findStatic(AppKitSpike.class, "runPending",
					MethodType.methodType(void.class, MemorySegment.class)), FunctionDescriptor.ofVoid(P), A);
		} catch (Throwable t) { throw new ExceptionInInitializerError(t); }
	}

	static MethodHandle send(String key, MemoryLayout ret, MemoryLayout... args) {
		return sends.computeIfAbsent(key, k -> {
			MemoryLayout[] all = new MemoryLayout[args.length + 2];
			all[0] = P; all[1] = P;
			System.arraycopy(args, 0, all, 2, args.length);
			return LINKER.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
					ret == null ? FunctionDescriptor.ofVoid(all) : FunctionDescriptor.of(ret, all));
		});
	}
	static MemorySegment sel(String n) {
		return sels.computeIfAbsent(n, k -> { try { return (MemorySegment) selReg.invokeExact(A.allocateFrom(k)); }
			catch (Throwable t) { throw new RuntimeException(t); } });
	}
	static MemorySegment cls(String n) {
		return classes.computeIfAbsent(n, k -> { try { return (MemorySegment) getClass_.invokeExact(A.allocateFrom(k)); }
			catch (Throwable t) { throw new RuntimeException(t); } });
	}
	static MemorySegment str(String s) throws Throwable {
		return (MemorySegment) send("id:id", P, P).invokeExact(cls("NSString"), sel("stringWithUTF8String:"), A.allocateFrom(s));
	}
	static MemorySegment id(long addr) { return MemorySegment.ofAddress(addr); }

	public static void runPending(MemorySegment ctx) {
		Object[] slot = pending.poll();
		try { slot[1] = ((Body<?>) slot[0]).run(); }
		catch (Throwable t) { slot[1] = t; }
	}

	/** every AppKit call goes through here: run it on thread 0 and wait */
	static <T> T onMain(Body<T> body) {
		try {
			// re-entrancy: a callback ALREADY runs on thread 0, and dispatch_sync to the
			// queue you are on is a deadlock. Run it inline instead.
			if ((int) isMain.invokeExact() != 0) return body.run();
			Object[] slot = { body, null };
			pending.put(slot);
			syncF.invokeExact(mainQ, MemorySegment.NULL, onMainStub);
			if (slot[1] instanceof Throwable t) throw new RuntimeException(t);
			@SuppressWarnings("unchecked") T r = (T) slot[1];
			return r;
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	/** IMP for -[RontoTarget invoke:] */
	public static void action(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
		Runnable r = actions.get(sender.address());
		if (r != null) r.run();
	}

	public static long window(String title, double w, double h) {
		return onMain(() -> {
			MethodHandle id0 = send("id:", P);
			MemorySegment app = (MemorySegment) id0.invokeExact(cls("NSApplication"), sel("sharedApplication"));
			boolean ok = (boolean) send("b:l", B, L).invokeExact(app, sel("setActivationPolicy:"), 0L);
			send("v:", null).invokeExact(app, sel("finishLaunching"));
			MemorySegment win = (MemorySegment) id0.invokeExact(cls("NSWindow"), sel("alloc"));
			try (Arena t = Arena.ofConfined()) {
				MemorySegment r = t.allocate(CGRECT);
				r.setAtIndex(D, 0, 0); r.setAtIndex(D, 1, 0); r.setAtIndex(D, 2, w); r.setAtIndex(D, 3, h);
				win = (MemorySegment) send("id:rect,l,l,b", P, CGRECT, L, L, B).invokeExact(win,
						sel("initWithContentRect:styleMask:backing:defer:"), r, 15L, 2L, false);
			}
			send("v:id", null, P).invokeExact(win, sel("setTitle:"), str(title));
			send("v:", null).invokeExact(win, sel("center"));
			send("v:id", null, P).invokeExact(win, sel("makeKeyAndOrderFront:"), MemorySegment.NULL);
			send("v:b", null, B).invokeExact(app, sel("activateIgnoringOtherApps:"), true);
			return win.address();
		});
	}

	public static long windowNumber(long win) {
		return onMain(() -> (long) send("l:", L).invokeExact(id(win), sel("windowNumber")));
	}

	public static long label(long win, String text, double x, double y, double w, double h) {
		return onMain(() -> {
			MemorySegment lbl = (MemorySegment) send("id:id", P, P).invokeExact(cls("NSTextField"),
					sel("labelWithString:"), str(text));
			try (Arena t = Arena.ofConfined()) {
				MemorySegment r = t.allocate(CGRECT);
				r.setAtIndex(D, 0, x); r.setAtIndex(D, 1, y); r.setAtIndex(D, 2, w); r.setAtIndex(D, 3, h);
				send("v:rect", null, CGRECT).invokeExact(lbl, sel("setFrame:"), r);
			}
			MemorySegment content = (MemorySegment) send("id:", P).invokeExact(id(win), sel("contentView"));
			send("v:id", null, P).invokeExact(content, sel("addSubview:"), lbl);
			return lbl.address();
		});
	}

	public static void setText(long view, String text) {
		onMain(() -> { send("v:id", null, P).invokeExact(id(view), sel("setStringValue:"), str(text)); return null; });
	}

	public static long button(long win, String title, double x, double y, double w, double h, Runnable onClick) {
		return onMain(() -> {
			if (targetClass == null) {
				targetClass = (MemorySegment) allocClassPair.invokeExact(cls("NSObject"), A.allocateFrom("RontoTarget"), 0L);
				MemorySegment imp = LINKER.upcallStub(MethodHandles.lookup().findStatic(AppKitSpike.class, "action",
						MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
						FunctionDescriptor.ofVoid(P, P, P), A);
				boolean added = (boolean) addMethod.invokeExact(targetClass, sel("invoke:"), imp, A.allocateFrom("v@:@"));
				registerClassPair.invokeExact(targetClass);
			}
			MethodHandle id0 = send("id:", P);
			MemorySegment handler = (MemorySegment) id0.invokeExact(
					(MemorySegment) id0.invokeExact(targetClass, sel("alloc")), sel("init"));
			MemorySegment btn = (MemorySegment) id0.invokeExact(cls("NSButton"), sel("alloc"));
			try (Arena t = Arena.ofConfined()) {
				MemorySegment r = t.allocate(CGRECT);
				r.setAtIndex(D, 0, x); r.setAtIndex(D, 1, y); r.setAtIndex(D, 2, w); r.setAtIndex(D, 3, h);
				btn = (MemorySegment) send("id:rect", P, CGRECT).invokeExact(btn, sel("initWithFrame:"), r);
			}
			send("v:id", null, P).invokeExact(btn, sel("setTitle:"), str(title));
			send("v:l", null, L).invokeExact(btn, sel("setBezelStyle:"), 1L);
			send("v:id", null, P).invokeExact(btn, sel("setTarget:"), handler);
			send("v:sel", null, P).invokeExact(btn, sel("setAction:"), sel("invoke:"));
			MemorySegment content = (MemorySegment) id0.invokeExact(id(win), sel("contentView"));
			send("v:id", null, P).invokeExact(content, sel("addSubview:"), btn);
			actions.put(btn.address(), onClick);
			return btn.address();
		});
	}

	/** so the button can be exercised without a human */
	public static void click(long btn) {
		onMain(() -> { send("v:id", null, P).invokeExact(id(btn), sel("performClick:"), MemorySegment.NULL); return null; });
	}
}
