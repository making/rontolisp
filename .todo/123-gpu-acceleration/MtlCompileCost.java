import java.lang.foreign.*;

/**
 * The PTX question, restated for Metal: what does getting the kernel onto the device cost at
 * startup, and does the OS cache it between processes the way the NVIDIA driver caches PTX in
 * ~/.nv/ComputeCache? Run this several times in a row -- the second run is the answer.
 */
public class MtlCompileCost {
	public static void main(String[] x) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		long t0 = System.nanoTime();
		MemorySegment dev = Mtl.device();
		double devMs = (System.nanoTime() - t0) / 1e6;
		t0 = System.nanoTime();
		MemorySegment lib = Mtl.library(dev, MtlSpike.SRC, MemorySegment.NULL);
		double libMs = (System.nanoTime() - t0) / 1e6;
		t0 = System.nanoTime();
		Mtl.pipeline(dev, lib, "gemm_f32");
		double psoMs = (System.nanoTime() - t0) / 1e6;
		t0 = System.nanoTime();
		Mtl.pipeline(dev, lib, "add_f32");
		double pso2Ms = (System.nanoTime() - t0) / 1e6;
		t0 = System.nanoTime();
		Mtl.library(dev, MtlSpike.SRC, MemorySegment.NULL);
		double libAgainMs = (System.nanoTime() - t0) / 1e6;
		System.out.printf("MTLCreateSystemDefaultDevice %6.1f ms | newLibraryWithSource %6.1f ms"
				+ " | same source again %6.1f ms | 1st pipeline %5.1f ms | 2nd pipeline %5.1f ms%n", devMs, libMs,
				libAgainMs, psoMs, pso2Ms);
		Mtl.poolPop.invokeExact(pool);
	}
}
