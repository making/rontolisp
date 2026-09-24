# Native-executable output: the spikes behind .todo/942-946

Measured 2026-09-24 on macOS 26 / arm64 (Darwin 25.3), Java 25.0.3, Oracle GraalVM
25.0.3, wasmtime CLI 47.0.3. Nothing here is wired into the build; each directory is
a standalone reproduction. `precomp/` and `runner/` are superseded by the maintained
workspace `rontolisp-native/` (`.kb/native-output.md`), whose trailer adds an 8-byte magic.

## Layout

| Dir | What it is |
|---|---|
| `precomp/` | cdylib, C ABI `rl_precompile(wasm, len, &out, &out_len) -> i32` / `rl_free`: `.wasm` bytes in, wasmtime-precompiled (Cranelift) module bytes out. 5.5 MB release. |
| `runner/` | The stub every output starts with: reads its own file, takes the trailing u64 as the payload length, deserializes the module and runs `_start` under WASI Preview 1. Runtime-only build (no Cranelift) is 1.7 MB; `--features compiler` adds a `--compile in.wasm out.cwasm` mode. `RL_DRC` / `RL_RES` env knobs exist only for the collector experiments below. |
| `native-spike/` | `NativeSpike.java` (package `am.ik.rontolisp.cli`, because `CompileFrontend` is package-private): `.lisp` -> `CompileFrontend.run` + `WasmLispCompiler` -> FFM `rl_precompile` -> `runner + cwasm + u64 len` -> ONE output file. Loads both natives from classpath resources `rlnative/`, extracts the dylib to `$RL_CACHE/<sha256-16>/`. `reachability-metadata.json` registers the two downcall shapes and the resources for native-image. |
| `clif-direct/` | The rejected alternative: a cdylib over `cranelift-reader` + `cranelift-object` (CLIF text + symbol table in, relocatable object out) and `ClifSpike.java`, a fixnum-only Lisp subset -> CLIF -> FFM -> `.o` -> `cc` with `rt.c`. |
| `c-api-runner/` | The same runner written against Homebrew's `libwasmtime.a` C API. Works, but see "collector". |
| `programs/` | `fib.lisp` (fib 35 + tak) and `gc.lisp` (3M string conses, `handler-case`, `equal` hash, bignum). |

## Reproduce

wasmtime 47 needs rustc >= 1.96 (the crates' MSRV; 1.93 resolves at most 45).

```bash
(cd precomp && cargo build --release)
(cd runner && cargo build --release && cp target/release/rlrun rlrun-runtime)
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar      # from the repo root, absolute path
mkdir -p cls/rlnative cls/META-INF/native-image/spike
cp precomp/target/release/librlprecomp.dylib runner/rlrun-runtime cls/rlnative/
cp native-spike/reachability-metadata.json cls/META-INF/native-image/spike/
javac -cp "$JAR" -d cls native-spike/NativeSpike.java
# JVM leg
RL_CACHE=$PWD/cache java --enable-native-access=ALL-UNNAMED -cp "${JAR}:cls" \
  am.ik.rontolisp.cli.NativeSpike programs/gc.lisp gc && ./gc
# native-image leg (same buildArgs as -Pnative)
native-image --no-fallback --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector \
  -H:+UnlockExperimentalVMOptions -H:+VectorAPISupport -cp "${JAR}:cls" \
  am.ik.rontolisp.cli.NativeSpike -o rl-native-spike
RL_CACHE=$PWD/cache ./rl-native-spike programs/gc.lisp gc && ./gc
```

zsh trap: `$JAR:cls` is a modifier expansion (`:c`), not a path join. Write `${JAR}:cls`.

## Numbers

Native-image leg (70 MB spike binary, 40 s build), `env -i` (no PATH, no HOME):

| | fib | gc |
|---|---|---|
| frontend + `.wasm` | 177 ms (2,865 B) | 197 ms (28,533 B) |
| FFM precompile | 179 ms first call (dylib load), 6 ms warm | 42 ms (272,552 B) |
| output | 1,803,680 B | 2,005,856 B |
| run | 0.10 s | 1.32 s |

Same programs elsewhere: `wasmtime run gc.wasm` 1.39 s; `java Gc` (JVM backend) 0.51 s;
`java Fib` 0.08 s; hello-world native output starts in < 10 ms (`java -jar` interpreter
0.16 s). Output depends only on `libSystem` + `libiconv` (`otool -L`).

The direct CLIF spike ran fib+tak in 0.03 s from a 33 KB executable -- the gap to 0.10 s is
the value representation (raw i64 vs the GC backend's boxed values), not the code
generator: wasmtime lowers the `.wasm` through Cranelift as well.

## Traps found

- **Collector.** The CLI's default is the copying collector; the C API's is DRC and the
  Homebrew 47 headers expose no setter. A module precompiled for one is refused by the other
  ("compiled for the copying collector but the host is configured to use the deferred
  reference-counting collector"). DRC ran `gc.lisp` in 12.1 s vs 1.4 s copying -- 9x.
  Hence the Rust crates instead of the C API.
- **wasmtime 45's copying collector** fails the backend's heap pregrow
  (`.kb/wasm-gc-heap-pregrow.md`): "GC heap out of memory: no capacity for allocation of
  16777236 bytes" on every program, `hello` included. DRC on 45 works; copying on 47 works.
- **Engine config is part of the artifact.** The precompile and the runner must build the
  same `Config` (GC, function-references, exceptions, tail-call, collector, heap
  reservation) from the same wasmtime version; `RL_RES=8589934592` on the runner alone is
  refused ("compiled with a GC heap reservation of '4294967296' but '8589934592'").
- **macOS signature.** Appending the payload leaves the linker's ad-hoc signature covering
  only the stub: `codesign -v` says "main executable failed strict validation" and
  `spctl` rejects it. It still runs from a shell, quarantined or not.
- **install_name.** The dylib's `LC_ID_DYLIB` is its cargo build path; harmless for
  `dlopen` by path, but build with `-C link-args=-Wl,-install_name,@rpath/librlprecomp.dylib`.
