import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/** Is the cuBLAS SGEMM number genuine FP32, or TF32 tensor cores (10-bit mantissa)? */
public class Tf32Check {
	public static void main(String[] x) throws Throwable {
		Cu.check((int) Cu.cuInit.invoke(0), "cuInit");
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment dev = ar.allocate(Cu.I);
			Cu.cuDeviceGet.invoke(dev, 0);
			MemorySegment pctx = ar.allocate(Cu.P);
			Cu.cuDevicePrimaryCtxRetain.invoke(pctx, dev.get(Cu.I, 0));
			Cu.cuCtxSetCurrent.invoke(pctx.get(Cu.P, 0));
			SymbolLookup lk = SymbolLookup.libraryLookup("libcublas.so.13", Arena.global());
			MethodHandle create = Cu.h(lk, "cublasCreate_v2", FunctionDescriptor.of(Cu.I, Cu.P));
			MethodHandle sgemm = Cu.h(lk, "cublasSgemm_v2",
					FunctionDescriptor.of(Cu.I, Cu.P, Cu.I, Cu.I, Cu.I, Cu.I, Cu.I, Cu.P, Cu.L, Cu.I, Cu.L, Cu.I, Cu.P,
							Cu.L, Cu.I));
			MethodHandle getMath = Cu.h(lk, "cublasGetMathMode", FunctionDescriptor.of(Cu.I, Cu.P, Cu.P));
			MemorySegment ph = ar.allocate(Cu.P);
			create.invoke(ph);
			MemorySegment h = ph.get(Cu.P, 0);
			MemorySegment mm = ar.allocate(Cu.I);
			getMath.invoke(h, mm);
			System.out.println("cublasGetMathMode = " + mm.get(Cu.I, 0)
					+ "  (0 = CUBLAS_DEFAULT_MATH, 1 = TF32_TENSOR_OP, 3 = PEDANTIC)");

			// 1 + 2^-20 is representable in fp32 (24-bit mantissa) but NOT in tf32 (11-bit).
			// C = A*B with A = [1+2^-20], B = [1] over a 32x32 matrix: an fp32 product keeps
			// the low bit, a tf32 product rounds it away to exactly 1.0.
			int n = 32;
			float eps = Math.scalb(1.0f, -20);
			float[] A = new float[n * n], B = new float[n * n], C = new float[n * n];
			for (int i = 0; i < n; i++) {
				A[i * n + i] = 1.0f + eps; // diagonal, so C = B scaled by (1+eps), no summation
				B[i * n + i] = 1.0f;
			}
			long bytes = (long) n * n * 4;
			MemorySegment pa = ar.allocate(Cu.L), pb = ar.allocate(Cu.L), pc = ar.allocate(Cu.L);
			Cu.cuMemAlloc.invoke(pa, bytes);
			Cu.cuMemAlloc.invoke(pb, bytes);
			Cu.cuMemAlloc.invoke(pc, bytes);
			long da = pa.get(Cu.L, 0), db = pb.get(Cu.L, 0), dc = pc.get(Cu.L, 0);
			MemorySegment hs = ar.allocate(ValueLayout.JAVA_FLOAT, n * n);
			MemorySegment.copy(A, 0, hs, ValueLayout.JAVA_FLOAT, 0, A.length);
			Cu.cuMemcpyHtoD.invoke(da, hs, bytes);
			MemorySegment.copy(B, 0, hs, ValueLayout.JAVA_FLOAT, 0, B.length);
			Cu.cuMemcpyHtoD.invoke(db, hs, bytes);
			MemorySegment alpha = ar.allocate(4), beta = ar.allocate(4);
			alpha.set(ValueLayout.JAVA_FLOAT, 0, 1.0f);
			beta.set(ValueLayout.JAVA_FLOAT, 0, 0.0f);
			sgemm.invoke(h, 0, 0, n, n, n, alpha, db, n, da, n, beta, dc, n);
			Cu.cuCtxSynchronize.invoke();
			Cu.cuMemcpyDtoH.invoke(hs, dc, bytes);
			MemorySegment.copy(hs, ValueLayout.JAVA_FLOAT, 0, C, 0, C.length);
			System.out.printf("input   1+2^-20 = %.9f (bits %08x)%n", 1.0f + eps, Float.floatToIntBits(1.0f + eps));
			System.out.printf("cuBLAS  C[0]    = %.9f (bits %08x)%n", C[0], Float.floatToIntBits(C[0]));
			System.out.println(C[0] == 1.0f + eps ? "=> low bit SURVIVED: genuine FP32, not TF32"
					: "=> low bit LOST: TF32 tensor cores were used");
		}
	}
}
