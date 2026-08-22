import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.*;

/**
 * The 2026-08-22 residency question, isolated from the interpreter: does the mere
 * EXISTENCE of live stream-ordered allocations make the next allocate / copy / free cycle
 * slower? The first residency build of am.ik.gpu halved the uploads of a training step
 * and made the step slower anyway -- nsys said the device-to-host copies had grown a tail
 * (619 copies over 1 ms against 39, a 4 KB copy among them) -- and the only thing the
 * cache changes for a copy is that other buffers are alive while it runs.
 *
 * Measures one 1 MB f32 buffer's alloc + HtoD + DtoH + free cycle (median / p90 / max of
 * 300) with nothing else alive, with K live 1 MB blocks the pool handed out earlier (each
 * touched once by an upload, as an operand would be), and again after they are freed;
 * then the GROWING pattern -- alloc and keep, as a result buffer kept resident is -- and
 * finally the same with the pool's release threshold raised to the maximum (argument
 * "keep"), since the default threshold 0 hands the reserve back at every synchronization.
 *
 * java --enable-native-access=ALL-UNNAMED .todo/123-gpu-acceleration/ResidencyCost.java
 * [keep]
 */
public class ResidencyCost {

	static final MethodHandle cuMemPoolSetAttribute = CuLib.h("cuMemPoolSetAttribute",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

	static final MethodHandle cuMemPoolGetAttribute = CuLib.h("cuMemPoolGetAttribute",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		boolean keep = args.length > 0 && args[0].equals("keep");
		int bytes = 1 << 20;
		float[] host = new float[bytes / 4];
		MemorySegment seg = MemorySegment.ofArray(host);
		try (Arena a = Arena.ofConfined()) {
			if (keep) {
				MemorySegment v = a.allocate(JAVA_LONG);
				v.set(JAVA_LONG, 0, -1L);
				CuLib.ck((int) cuMemPoolSetAttribute.invoke(CuLib.pool, 4, v), "cuMemPoolSetAttribute");
			}
			System.out.printf("release threshold: %s%n", keep ? "max" : "driver default (0)");
			System.out.printf("%-44s %10s %10s %10s | %10s %10s %10s%n", "", "cycle med", "p90", "max", "DtoH med",
					"p90", "max");
			report("nothing else alive", a, seg, bytes, false);
			report("nothing else alive, sync before DtoH", a, seg, bytes, true);
			for (int k : new int[] { 64, 512, 2048 }) {
				long[] live = new long[k];
				for (int i = 0; i < k; i++) {
					live[i] = CuLib.alloc(a, bytes, true);
					CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(live[i], seg, (long) bytes), "HtoD");
				}
				CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
				report(k + " live 1 MB blocks, touched", a, seg, bytes, false);
				report(k + " live 1 MB blocks, touched, sync before DtoH", a, seg, bytes, true);
				for (long p : live) {
					CuLib.free(p, true);
				}
				CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
				report("after freeing them", a, seg, bytes, false);
				reserved();
			}
			// The growing pattern: a result buffer kept resident is an allocation that is
			// not freed before the next one.
			long[] kept = new long[300];
			double[] dtoh = new double[300];
			for (int i = 0; i < 300; i++) {
				kept[i] = CuLib.alloc(a, bytes, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(kept[i], seg, (long) bytes), "HtoD");
				long t = System.nanoTime();
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(seg, kept[i], (long) bytes), "DtoH");
				dtoh[i] = (System.nanoTime() - t) / 1e3;
			}
			Arrays.sort(dtoh);
			System.out.printf("%-44s %10s %10s %10s | %10.1f %10.1f %10.1f%n",
					"growing: alloc+HtoD+DtoH, never freed (300)", "", "", "", dtoh[150], dtoh[270], dtoh[299]);
			for (long p : kept) {
				CuLib.free(p, true);
			}
			CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
			reserved();
		}
	}

	static void reserved() throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment v = a.allocate(JAVA_LONG);
			CuLib.ck((int) cuMemPoolGetAttribute.invoke(CuLib.pool, 5, v), "get RESERVED_MEM_CURRENT");
			long reserved = v.get(JAVA_LONG, 0);
			CuLib.ck((int) cuMemPoolGetAttribute.invoke(CuLib.pool, 7, v), "get USED_MEM_CURRENT");
			System.out.printf("    pool reserved %d MB, used %d MB%n", reserved >> 20, v.get(JAVA_LONG, 0) >> 20);
		}
	}

	static void report(String label, Arena a, MemorySegment seg, int bytes, boolean sync) throws Throwable {
		int reps = 300;
		double[] cycle = new double[reps], dtoh = new double[reps];
		for (int i = 0; i < reps; i++) {
			long t0 = System.nanoTime();
			long d = CuLib.alloc(a, bytes, true);
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(d, seg, (long) bytes), "HtoD");
			if (sync) {
				CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
			}
			long t1 = System.nanoTime();
			CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(seg, d, (long) bytes), "DtoH");
			long t2 = System.nanoTime();
			CuLib.free(d, true);
			cycle[i] = (System.nanoTime() - t0) / 1e3;
			dtoh[i] = (t2 - t1) / 1e3;
		}
		Arrays.sort(cycle);
		Arrays.sort(dtoh);
		System.out.printf("%-44s %10.1f %10.1f %10.1f | %10.1f %10.1f %10.1f%n", label, cycle[reps / 2],
				cycle[reps * 9 / 10], cycle[reps - 1], dtoh[reps / 2], dtoh[reps * 9 / 10], dtoh[reps - 1]);
	}

}
