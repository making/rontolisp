import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Callbacks: a runtime-registered ObjC class whose IMP is an FFM upcall stub. */
public class NativeImageSpike {
	static final Linker LINKER = Linker.nativeLinker();
	static final AddressLayout P = ValueLayout.ADDRESS;
	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
	static final ValueLayout.OfBoolean B = ValueLayout.JAVA_BOOLEAN;
	static final MemoryLayout CGRECT = MemoryLayout.structLayout(D, D, D, D);
	static final Arena A = Arena.global();

	static SymbolLookup objc, libsys;
	static MethodHandle getClass_, selReg, poolPush, poolPop, allocClassPair, addMethod, registerClassPair, asyncF;
	static final Map<String, MemorySegment> sels = new HashMap<>(), classes = new HashMap<>();
	static final Map<String, MethodHandle> sends = new HashMap<>();
	static final AtomicInteger clicks = new AtomicInteger();
	static MemorySegment label, app;

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
	static MemorySegment rect(Arena a, double x, double y, double w, double h) {
		MemorySegment r = a.allocate(CGRECT);
		r.setAtIndex(D, 0, x); r.setAtIndex(D, 1, y); r.setAtIndex(D, 2, w); r.setAtIndex(D, 3, h);
		return r;
	}

	/** IMP for -[RontoTarget invoke:] : void (id self, SEL _cmd, id sender) */
	public static void action(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
		int n = clicks.incrementAndGet();
		System.out.println("ACTION fired, click #" + n + " sender=" + sender.address());
		try { send("v:id", null, P).invokeExact(label, sel("setStringValue:"), str("clicked " + n + " time(s)")); }
		catch (Throwable t) { t.printStackTrace(); }
	}

	static volatile boolean built = false;
	static MemorySegment button;

	public static void build(MemorySegment ctx) {
		try {
			MemorySegment pool = (MemorySegment) poolPush.invokeExact();
			MethodHandle id0 = send("id:", P), idId = send("id:id", P, P), voidId = send("v:id", null, P),
					void0 = send("v:", null), boolLong = send("b:l", B, L), voidBool = send("v:b", null, B),
					voidSel = send("v:sel", null, P);
			app = (MemorySegment) id0.invokeExact(cls("NSApplication"), sel("sharedApplication"));
			boolean unusedPolicy = (boolean) boolLong.invokeExact(app, sel("setActivationPolicy:"), 0L);

			// --- a class defined at runtime, its method body a Java upcall ------------
			MemorySegment name = A.allocateFrom("RontoTarget");
			MemorySegment target = (MemorySegment) allocClassPair.invokeExact(cls("NSObject"), name, 0L);
			MethodHandle impTarget = MethodHandles.lookup().findStatic(NativeImageSpike.class, "action",
					MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
			MemorySegment imp = LINKER.upcallStub(impTarget, FunctionDescriptor.ofVoid(P, P, P), A);
			boolean added = (boolean) addMethod.invokeExact(target, sel("invoke:"), imp, A.allocateFrom("v@:@"));
			registerClassPair.invokeExact(target);
			System.out.println("class_addMethod = " + added);
			MemorySegment handler = (MemorySegment) id0.invokeExact(
					(MemorySegment) id0.invokeExact(target, sel("alloc")), sel("init"));

			MemorySegment win = (MemorySegment) id0.invokeExact(cls("NSWindow"), sel("alloc"));
			try (Arena t = Arena.ofConfined()) {
				win = (MemorySegment) send("id:rect,l,l,b", P, CGRECT, L, L, B).invokeExact(win,
						sel("initWithContentRect:styleMask:backing:defer:"), rect(t, 0, 0, 480, 220), 15L, 2L, false);
			}
			voidId.invokeExact(win, sel("setTitle:"), str("rontolisp GUI spike -- callbacks"));
			MemorySegment content = (MemorySegment) id0.invokeExact(win, sel("contentView"));

			label = (MemorySegment) idId.invokeExact(cls("NSTextField"), sel("labelWithString:"), str("no clicks yet"));
			try (Arena t = Arena.ofConfined()) {
				send("v:rect", null, CGRECT).invokeExact(label, sel("setFrame:"), rect(t, 40, 130, 400, 30));
			}
			voidId.invokeExact(content, sel("addSubview:"), label);

			button = (MemorySegment) id0.invokeExact(cls("NSButton"), sel("alloc"));
			try (Arena t = Arena.ofConfined()) {
				button = (MemorySegment) send("id:rect", P, CGRECT).invokeExact(button, sel("initWithFrame:"),
						rect(t, 40, 50, 160, 40));
			}
			voidId.invokeExact(button, sel("setTitle:"), str("Click me"));
			send("v:l", null, L).invokeExact(button, sel("setBezelStyle:"), 1L);
			voidId.invokeExact(button, sel("setTarget:"), handler);
			voidSel.invokeExact(button, sel("setAction:"), sel("invoke:"));
			voidId.invokeExact(content, sel("addSubview:"), button);

			void0.invokeExact(win, sel("center"));
			voidId.invokeExact(win, sel("makeKeyAndOrderFront:"), MemorySegment.NULL);
			voidBool.invokeExact(app, sel("activateIgnoringOtherApps:"), true);
			System.out.println("windowNumber = " + (long) send("l:", L).invokeExact(win, sel("windowNumber")));
			poolPop.invokeExact(pool);
			built = true;
			void0.invokeExact(app, sel("finishLaunching"));
		} catch (Throwable t) { t.printStackTrace(); built = true; }
	}

	/** dispatched later, from the REPL thread: does a live window take new work? */
	public static void click(MemorySegment ctx) {
		try { send("v:id", null, P).invokeExact(button, sel("performClick:"), MemorySegment.NULL); }
		catch (Throwable t) { t.printStackTrace(); }
	}

	/** constant lookups: native image folds these, so no reflection metadata is needed */
	static MemorySegment stub(String which) throws Throwable {
		MethodHandle m = which.equals("build")
				? MethodHandles.lookup().findStatic(NativeImageSpike.class, "build",
						MethodType.methodType(void.class, MemorySegment.class))
				: MethodHandles.lookup().findStatic(NativeImageSpike.class, "click",
						MethodType.methodType(void.class, MemorySegment.class));
		return LINKER.upcallStub(m, FunctionDescriptor.ofVoid(P), A);
	}

	static void onMain(String method) throws Throwable {
		asyncF.invokeExact(libsys.find("_dispatch_main_q").orElseThrow(), MemorySegment.NULL, stub(method));
	}

	public static void main(String[] args) throws Throwable {
		objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", A);
		libsys = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", A);
		SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", A);
		getClass_ = LINKER.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(P, P));
		selReg = LINKER.downcallHandle(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(P, P));
		poolPush = LINKER.downcallHandle(objc.find("objc_autoreleasePoolPush").orElseThrow(), FunctionDescriptor.of(P));
		poolPop = LINKER.downcallHandle(objc.find("objc_autoreleasePoolPop").orElseThrow(), FunctionDescriptor.ofVoid(P));
		allocClassPair = LINKER.downcallHandle(objc.find("objc_allocateClassPair").orElseThrow(), FunctionDescriptor.of(P, P, P, L));
		addMethod = LINKER.downcallHandle(objc.find("class_addMethod").orElseThrow(), FunctionDescriptor.of(B, P, P, P, P));
		registerClassPair = LINKER.downcallHandle(objc.find("objc_registerClassPair").orElseThrow(), FunctionDescriptor.ofVoid(P));
		asyncF = LINKER.downcallHandle(libsys.find("dispatch_async_f").orElseThrow(), FunctionDescriptor.ofVoid(P, P, P));

		MethodHandle isMain = LINKER.downcallHandle(libsys.find("pthread_main_np").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		System.out.println("java main pthread_main_np() = " + (int) isMain.invokeExact());
		Thread repl = new Thread(() -> {
			try {
				System.out.println("-- REPL thread started; asking thread 0 for a window --");
				onMain("build");
				while (!built) Thread.sleep(50);
				System.out.println("-- REPL thread: window is up, now driving it --");
				Thread.sleep(1500);
				onMain("click");
				Thread.sleep(1500);
				System.out.println("clicks recorded = " + clicks.get());
			} catch (Throwable t) { t.printStackTrace(); }
		}, "repl");
		repl.setDaemon(true);
		repl.start();
		// thread 0 idles in a CFRunLoop -- exactly what the java launcher does for us
		SymbolLookup cf = SymbolLookup.libraryLookup(
				"/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", A);
		MethodHandle runLoop = LINKER.downcallHandle(cf.find("CFRunLoopRun").orElseThrow(),
				FunctionDescriptor.ofVoid());
		System.out.println("thread 0 entering CFRunLoopRun");
		runLoop.invokeExact();
	}
}
