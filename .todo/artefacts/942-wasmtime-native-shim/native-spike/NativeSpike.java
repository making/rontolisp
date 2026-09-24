package am.ik.rontolisp.cli;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.*;
import java.nio.file.*;

/** prog.lisp -> (memory) .wasm -> (FFM, memory) .cwasm -> runner + cwasm + len -> prog. Only prog is written. */
public class NativeSpike {
    static byte[] resource(String name) throws java.io.IOException {
        try (var in = NativeSpike.class.getClassLoader().getResourceAsStream(name)) { return in.readAllBytes(); }
    }

    public static void main(String[] a) throws Throwable {
        Path src = Path.of(a[0]), out = Path.of(a[1]);
        // Both natives travel inside this program as resources; the dylib is extracted once to a
        // content-addressed cache path because dlopen needs a real file.
        byte[] dylibBytes = resource("rlnative/librlprecomp.dylib"), stub = resource("rlnative/rlrun-runtime");
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(dylibBytes)).substring(0, 16);
        Path dylib = Path.of(System.getenv("RL_CACHE"), hash, "librlprecomp.dylib");
        if (!Files.exists(dylib)) {
            Files.createDirectories(dylib.getParent());
            Path tmp = Files.createTempFile(dylib.getParent(), "lib", ".tmp");
            Files.write(tmp, dylibBytes);
            Files.move(tmp, dylib, StandardCopyOption.ATOMIC_MOVE);
            System.err.println("extracted " + dylib);
        }
        long t0 = System.nanoTime();
        CompileFrontend.Result fe = CompileFrontend.run(CompileFrontend.Request.builder()
            .source(Files.readString(src)).entryFile(src.toString())
            .options(CompileFrontend.Options.builder().baseDir(src.toAbsolutePath().getParent().toString()).wasm(true).build())
            .build());
        byte[] wasm = WasmLispCompiler.builder().runtimeFeatures(fe.features().names()).build().compile(fe.program());
        long t1 = System.nanoTime();

        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(dylib, Arena.global());
        MethodHandle precompile = linker.downcallHandle(lib.find("rl_precompile").orElseThrow(), FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle free = linker.downcallHandle(lib.find("rl_free").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        byte[] cwasm;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outP = arena.allocate(ValueLayout.ADDRESS), outL = arena.allocate(ValueLayout.JAVA_LONG);
            int rc = (int) precompile.invokeExact(arena.allocateFrom(ValueLayout.JAVA_BYTE, wasm), (long) wasm.length, outP, outL);
            long len = outL.get(ValueLayout.JAVA_LONG, 0);
            MemorySegment p = outP.get(ValueLayout.ADDRESS, 0).reinterpret(len);
            cwasm = p.toArray(ValueLayout.JAVA_BYTE);
            free.invokeExact(p, len);
            if (rc != 0) throw new IllegalStateException(new String(cwasm));
        }
        long t2 = System.nanoTime();

        ByteBuffer b = ByteBuffer.allocate(stub.length + cwasm.length + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.put(stub).put(cwasm).putLong(cwasm.length);
        Files.write(out, b.array());
        out.toFile().setExecutable(true);
        System.err.printf("frontend+wasm %.0f ms (%d B), FFM precompile %.0f ms (%d B), wrote %s (%d B)%n",
            (t1 - t0) / 1e6, wasm.length, (t2 - t1) / 1e6, cwasm.length, out, b.capacity());
    }
}
