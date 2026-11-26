import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Can an AOT image ever fold a downcall handle? The ADDRESS-FIRST shape,
 * {@code Linker.downcallHandle(FunctionDescriptor)}, holds no native pointer, so a holder
 * class initialised at BUILD time can carry it in the image heap as a constant; the
 * function address is looked up at run time and passed as the leading argument. A probe.
 */
public class BuildTimeHandle {

	static final int REPS = 2_000_000;

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final FunctionDescriptor SHAPE = FunctionDescriptor.of(I, ValueLayout.ADDRESS);

	/** Initialised at build time under --initialize-at-build-time=BuildTimeHandle$Holder. */
	static final class Holder {

		static final MethodHandle ADDRESS_FIRST = Linker.nativeLinker().downcallHandle(SHAPE);

	}

	/** The run-time control: the same address-first shape, bound at run time. */
	static final MethodHandle RUNTIME_ADDRESS_FIRST = Linker.nativeLinker().downcallHandle(SHAPE);

	static final SymbolLookup CUDA = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());

	static final MemorySegment ADDR = CUDA.find("cuDriverGetVersion").orElseThrow();

	static final MethodHandle RUNTIME_BOUND = Linker.nativeLinker().downcallHandle(ADDR, SHAPE);

	public static void main(String[] args) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(I);
			for (int round = 0; round < 5; round++) {
				double a = ns(() -> (int) Holder.ADDRESS_FIRST.invokeExact(ADDR, out));
				double b = 0;
				double c = ns(() -> (int) RUNTIME_ADDRESS_FIRST.invokeExact(ADDR, out));
				double d = ns(() -> (int) RUNTIME_BOUND.invokeExact(out));
				System.out.printf("round %d  build-time %8.1f   build-time critical %8.1f   run-time address-first %8.1f   run-time bound %8.1f ns%n",
						round, a, b, c, d);
			}
		}
	}

	interface Call {

		int run() throws Throwable;

	}

	static double ns(Call call) throws Throwable {
		int sink = 0;
		for (int i = 0; i < REPS / 10; i++) {
			sink += call.run();
		}
		long start = System.nanoTime();
		for (int i = 0; i < REPS; i++) {
			sink += call.run();
		}
		long elapsed = System.nanoTime() - start;
		if (sink == Integer.MIN_VALUE) {
			throw new IllegalStateException();
		}
		return elapsed / (double) REPS;
	}

}
