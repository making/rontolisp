import java.lang.foreign.*;
import java.nio.file.*;

/** Emits PTX for a low virtual arch, to test the "ship PTX, need only libcuda.so.1" route. */
public class DumpPtx {
	public static void main(String[] a) throws Throwable {
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment prog = ar.allocate(Cu.P);
			int r = (int) Cu.nvrtcCreateProgram.invoke(prog, ar.allocateFrom(MatmulSpike.SRC), ar.allocateFrom("k.cu"),
					0, MemorySegment.NULL, MemorySegment.NULL);
			MemorySegment p = prog.get(Cu.P, 0);
			MemorySegment opts = ar.allocate(Cu.P, 2);
			opts.setAtIndex(Cu.P, 0, ar.allocateFrom("--gpu-architecture=compute_" + a[0]));
			opts.setAtIndex(Cu.P, 1, ar.allocateFrom("-default-device"));
			r = (int) Cu.nvrtcCompileProgram.invoke(p, 2, opts);
			MemorySegment ls = ar.allocate(Cu.L);
			Cu.nvrtcGetProgramLogSize.invoke(p, ls);
			if (ls.get(Cu.L, 0) > 1) {
				MemorySegment log = ar.allocate(ls.get(Cu.L, 0));
				Cu.nvrtcGetProgramLog.invoke(p, log);
				System.out.println(log.getString(0));
			}
			if (r != 0) throw new RuntimeException("compile " + r);
			Cu.nvrtcGetPTXSize.invoke(p, ls);
			MemorySegment ptx = ar.allocate(ls.get(Cu.L, 0));
			Cu.nvrtcGetPTX.invoke(p, ptx);
			Files.writeString(Path.of("gemm_" + a[0] + ".ptx"), ptx.getString(0));
			System.out.println("wrote gemm_" + a[0] + ".ptx (" + ls.get(Cu.L, 0) + " bytes)");
		}
	}
}
