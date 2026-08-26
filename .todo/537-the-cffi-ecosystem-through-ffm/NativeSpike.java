import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;

public class NativeSpike {
	static final Linker LINKER = Linker.nativeLinker();

	public static void main(String[] args) throws Throwable {
		Arena arena = Arena.ofConfined();
		SymbolLookup libc = LINKER.defaultLookup();
		// registered shape, built entirely at RUN time from strings
		MethodHandle strlen = LINKER.downcallHandle(libc.find("strlen").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
		System.out.println("strlen        = " + (long) strlen.invokeWithArguments(arena.allocateFrom("hello, world")));
		// a library dlopen'd at RUN time (define-foreign-library / use-foreign-library)
		try {
			SymbolLookup libm = SymbolLookup.libraryLookup("libm.so.6", arena);
			MethodHandle cos = LINKER.downcallHandle(libm.find("cos").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
			System.out.println("runtime dlopen= cos(0.0) " + (double) cos.invokeWithArguments(0.0d));
		}
		catch (Throwable ex) {
			System.out.println("runtime dlopen FAILED: " + ex);
		}
		// an UNREGISTERED shape
		try {
			MethodHandle abs = LINKER.downcallHandle(libc.find("abs").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			System.out.println("unregistered  = " + (int) abs.invokeWithArguments(-7));
		}
		catch (Throwable ex) {
			System.out.println("unregistered  = " + ex.getClass().getName() + ": " + ex.getMessage());
		}
		// an upcall built at run time
		try {
			int[] data = { 5, 3, 9, 1 };
			MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_INT, data);
			FunctionDescriptor cmpDesc = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS);
			MethodHandle target = java.lang.invoke.MethodHandles.lookup().findStatic(NativeSpike.class, "cmp",
					cmpDesc.toMethodType());
			MemorySegment cb = LINKER.upcallStub(target, cmpDesc, arena);
			MethodHandle qsort = LINKER.downcallHandle(libc.find("qsort").orElseThrow(), FunctionDescriptor.ofVoid(
					ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
			qsort.invokeWithArguments(buf, 4L, 4L, cb);
			System.out.println("upcall        = " + Arrays.toString(buf.toArray(ValueLayout.JAVA_INT)));
		}
		catch (Throwable ex) {
			System.out.println("upcall        = " + ex.getClass().getName() + ": " + ex.getMessage());
		}
		arena.close();
	}

	static int cmp(MemorySegment a, MemorySegment b) {
		return Integer.compare(a.reinterpret(4).get(ValueLayout.JAVA_INT, 0), b.reinterpret(4).get(ValueLayout.JAVA_INT, 0));
	}
}
