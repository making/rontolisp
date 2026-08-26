import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Spike: can CFFI's surface be served by FFM, with every type decided at RUN time? */
public class Spike {

	static final Linker LINKER = Linker.nativeLinker();

	/** The CFFI keyword type map, decided at run time from a symbol. */
	static MemoryLayout layout(String t) {
		return switch (t) {
			case ":char", ":uchar", ":int8", ":uint8" -> ValueLayout.JAVA_BYTE;
			case ":short", ":ushort", ":int16", ":uint16" -> ValueLayout.JAVA_SHORT;
			case ":int", ":uint", ":int32", ":uint32" -> ValueLayout.JAVA_INT;
			case ":long", ":ulong", ":int64", ":uint64", ":long-long" -> ValueLayout.JAVA_LONG;
			case ":float" -> ValueLayout.JAVA_FLOAT;
			case ":double" -> ValueLayout.JAVA_DOUBLE;
			case ":pointer", ":string" -> ValueLayout.ADDRESS;
			default -> throw new IllegalArgumentException("no such foreign type: " + t);
		};
	}

	/** A run-time-built downcall: name + signature strings in, Object out. */
	static Object call(SymbolLookup lib, String name, String ret, List<String> params, Object... args) {
		MemorySegment addr = lib.find(name).orElseThrow(() -> new RuntimeException("undefined foreign function: " + name));
		MemoryLayout[] ps = params.stream().map(Spike::layout).toArray(MemoryLayout[]::new);
		FunctionDescriptor fd = ret.equals(":void") ? FunctionDescriptor.ofVoid(ps)
				: FunctionDescriptor.of(layout(ret), ps);
		MethodHandle mh = LINKER.downcallHandle(addr, fd);
		try {
			return mh.invokeWithArguments(args);
		}
		catch (Throwable ex) {
			throw new RuntimeException(ex);
		}
	}

	public static void main(String[] args) throws Throwable {
		Arena arena = Arena.ofConfined();
		SymbolLookup libc = LINKER.defaultLookup();

		// 1. strlen(:string) -- a Lisp string marshalled to a C string
		MemorySegment hello = arena.allocateFrom("hello, world");
		System.out.println("strlen        = " + call(libc, "strlen", ":long", List.of(":pointer"), hello));

		// 2. a math call by double, from a NAMED library (define-foreign-library/use-foreign-library)
		SymbolLookup libm = SymbolLookup.libraryLookup("libm.so.6", arena);
		System.out.println("cos(0.0)      = " + call(libm, "cos", ":double", List.of(":double"), 0.0d));

		// 3. a third-party library + a :string RETURN (sqlite3_libversion)
		SymbolLookup sq = SymbolLookup.libraryLookup("libsqlite3.so.0", arena);
		MemorySegment v = (MemorySegment) call(sq, "sqlite3_libversion", ":pointer", List.of());
		System.out.println("sqlite ver    = " + v.reinterpret(Long.MAX_VALUE).getString(0));

		// 4. defcvar: a DATA symbol read and (where writable) written
		MemorySegment sym = sq.find("sqlite3_version").orElseThrow();
		System.out.println("defcvar       = " + sym.reinterpret(Long.MAX_VALUE).getString(0));

		// 5. with-foreign-object + mem-ref: out-parameters
		MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG, 2);
		Object rc = call(libc, "gettimeofday", ":int", List.of(":pointer", ":pointer"), out, MemorySegment.NULL);
		System.out.println("gettimeofday  = rc " + rc + " sec " + out.get(ValueLayout.JAVA_LONG, 0));

		// 6. defcstruct: a layout built at run time, slots by name
		StructLayout tv = MemoryLayout.structLayout(ValueLayout.JAVA_LONG.withName("tv_sec"),
				ValueLayout.JAVA_LONG.withName("tv_usec"));
		var sec = tv.varHandle(MemoryLayout.PathElement.groupElement("tv_sec"));
		System.out.println("struct slot   = " + sec.get(out, 0L) + " (size " + tv.byteSize() + ")");

		// 7. defcallback: a JAVA function called BY C (qsort)
		int[] data = { 5, 3, 9, 1 };
		MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_INT, data);
		FunctionDescriptor cmpDesc = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
		MethodHandle target = java.lang.invoke.MethodHandles.lookup()
			.findStatic(Spike.class, "cmp", cmpDesc.toMethodType());
		MemorySegment cb = LINKER.upcallStub(target, cmpDesc, arena);
		call(libc, "qsort", ":void", List.of(":pointer", ":long", ":long", ":pointer"), buf, 4L, 4L, cb);
		int[] sorted = buf.toArray(ValueLayout.JAVA_INT);
		System.out.println("qsort+upcall  = " + Arrays.toString(sorted));

		// 8. varargs (&rest in cffi's foreign-funcall)
		MemorySegment dst = arena.allocate(64);
		MemorySegment fmt = arena.allocateFrom("%s=%d");
		MethodHandle snprintf = LINKER.downcallHandle(libc.find("snprintf").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
						ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
				Linker.Option.firstVariadicArg(3));
		snprintf.invoke(dst, 64L, fmt, arena.allocateFrom("n"), 42);
		System.out.println("varargs       = " + dst.getString(0));

		// 9. errno capture (a real binding needs it: strerror(errno))
		StructLayout capture = Linker.Option.captureStateLayout();
		MemorySegment state = arena.allocate(capture);
		MethodHandle open = LINKER.downcallHandle(libc.find("open").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
				Linker.Option.captureCallState("errno"), Linker.Option.firstVariadicArg(2));
		int fd = (int) open.invoke(state, arena.allocateFrom("/no/such/file"), 0);
		var errnoH = capture.varHandle(MemoryLayout.PathElement.groupElement("errno"));
		System.out.println("errno         = fd " + fd + " errno " + errnoH.get(state, 0L));

		// 10. a wrong arity / wrong type is a clean error, not a crash?
		try {
			call(libm, "cos", ":double", List.of(":double"), "not a double");
		}
		catch (RuntimeException ex) {
			System.out.println("bad operand   = " + ex.getCause().getClass().getSimpleName());
		}

		// 11. cost of building a handle per call vs caching it
		long t0 = System.nanoTime();
		for (int i = 0; i < 10000; i++) {
			call(libm, "cos", ":double", List.of(":double"), 0.5d);
		}
		long perCallBuild = (System.nanoTime() - t0) / 10000;
		MethodHandle cached = LINKER.downcallHandle(libm.find("cos").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
		t0 = System.nanoTime();
		double acc = 0;
		for (int i = 0; i < 10000; i++) {
			acc += (double) cached.invokeExact(0.5d);
		}
		long perCallCached = (System.nanoTime() - t0) / 10000;
		System.out.println("cost/call     = build " + perCallBuild + "ns  cached " + perCallCached + "ns (" + acc + ")");
		arena.close();
	}

	static int cmp(MemorySegment a, MemorySegment b) {
		int x = a.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
		int y = b.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
		return Integer.compare(x, y);
	}
}
