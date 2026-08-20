import java.lang.foreign.*;

/**
 * The decisive question for Metal, and it has nothing to do with speed: CUDA's fp64 is 44x
 * slower than fp32 but it EXISTS. Does MSL have a double at all?
 */
public class MtlF64Probe {

	public static void main(String[] a) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		MemorySegment dev = Mtl.device();
		System.out.println("device: " + Mtl.fromNsString(Mtl.msg(dev, "name")));
		for (int f = 1000; f <= 1011; f++) { // MTLGPUFamilyApple1..12 are 1001..
			// supportsFamily: takes an NSInteger
			long r = supportsFamily(dev, f);
			if (r != 0) System.out.println("  supportsFamily " + f + " (Apple" + (f - 1000) + ") = yes");
		}
		try_("double", """
				#include <metal_stdlib>
				using namespace metal;
				kernel void k(device const double* a, device double* b, uint i [[thread_position_in_grid]]) {
				  b[i] = a[i] * 2.0;
				}
				""");
		try_("float", """
				#include <metal_stdlib>
				using namespace metal;
				kernel void k(device const float* a, device float* b, uint i [[thread_position_in_grid]]) {
				  b[i] = a[i] * 2.0f;
				}
				""");
		try_("half", """
				#include <metal_stdlib>
				using namespace metal;
				kernel void k(device const half* a, device half* b, uint i [[thread_position_in_grid]]) {
				  b[i] = a[i] * (half) 2.0;
				}
				""");
		try_("bfloat", """
				#include <metal_stdlib>
				using namespace metal;
				kernel void k(device const bfloat* a, device bfloat* b, uint i [[thread_position_in_grid]]) {
				  b[i] = a[i] * (bfloat) 2.0;
				}
				""");
		try_("long (64-bit int)", """
				#include <metal_stdlib>
				using namespace metal;
				kernel void k(device const long* a, device long* b, uint i [[thread_position_in_grid]]) {
				  b[i] = a[i] * 2;
				}
				""");
		Mtl.poolPop.invokeExact(pool);
	}

	static long supportsFamily(MemorySegment dev, int family) {
		try {
			return (long) Mtl.send(Mtl.L, Mtl.L).invokeExact(dev, Mtl.sel("supportsFamily:"), (long) family);
		}
		catch (Throwable t) {
			return 0;
		}
	}

	static void try_(String label, String src) {
		MemorySegment dev = Mtl.device();
		try {
			Mtl.library(dev, src, MemorySegment.NULL);
			System.out.printf("  %-18s COMPILES%n", label);
		}
		catch (RuntimeException e) {
			String m = e.getMessage().replace('\n', ' ');
			System.out.printf("  %-18s REJECTED: %s%n", label, m.length() > 300 ? m.substring(0, 300) + "..." : m);
		}
	}
}
