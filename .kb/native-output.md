# Native-executable output: the wasmtime shim and the runner stub

**Invariant**: a native output is `stub ++ module ++ u64-le(module length) ++ "RLNATIVE"`,
where `module` is the wasm-GC backend's Preview 1 module precompiled by the shim and `stub`
is the runner. Shim and stub build their engine from ONE function (`rlabi::config`) of ONE
exactly pinned wasmtime; a module precompiled under any other engine is refused by wasmtime
at start-up, so the Java side must compare fingerprints BEFORE writing an output.

Code: `rontolisp-native/` -- a Cargo workspace outside the Maven reactor (the core stays
dependency-free). `abi/` (`rlabi`: config, `FINGERPRINT`, `STUB_MARKER`, `payload`),
`precomp/` (`rlprecomp` cdylib + `rlpack` CLI), `runner/` (`rlrun`). Build and test:
`rontolisp-native/build.sh [--test]`; fixtures: `rontolisp-native/gen-fixtures.sh`.

## The two halves

- **Shim** `librlprecomp.{so,dylib}`: `rl_precompile(wasm, len, platform, cpu, &out, &out_len) -> i32`
  (`platform`, `cpu`: NUL-terminated, see "CPU baseline and cross-targets"; 0 = module,
  1 = compile error -- an unknown platform or CPU level included --, 2 = caught panic;
  `*out` holds the module or the UTF-8 message either way), `rl_free(out, out_len)`,
  `rl_version() -> const char*` (static `FINGERPRINT`). Features `cranelift` +
  `parallel-compilation`, plus Cranelift's `x86` and `arm64` backends through a direct
  `cranelift-codegen` dependency whose features unify into wasmtime's (its version moves
  with the wasmtime pin). The release profile keeps
  `panic = unwind` and `rl_precompile` catches at the boundary: it runs inside a JVM.
  `install_name @rpath/librlprecomp.dylib` / `soname librlprecomp.so` via `precomp/build.rs`.
- **Stub** `rlrun`: runtime-only wasmtime (`runtime std gc gc-copying`, no Cranelift) +
  `wasmtime-wasi` `p1`; profile `release-runner` (`panic = abort`). On Linux it links glibc
  STATICALLY (`-C target-feature=+crt-static -C relocation-model=static`, built with an
  explicit `--target <arch>-unknown-linux-gnu` so the flags stay off build scripts; binary
  at `target/<triple>/release-runner/rlrun`): an output has no glibc floor and runs on musl
  hosts too (checked 2026-09-24 in `alpine:3.20`, `centos:7` = glibc 2.17 and `busybox`,
  where the dynamic stub failed on `GLIBC_2.34` / `libgcc_s.so.1`). The relocation model
  keeps the stub a non-PIE static executable on every architecture: without it the x86_64
  stub linked as static-pie, which `qemu-x86_64` on an aarch64 host cannot run (QEMU itself
  dies with an internal SIGSEGV, MAPERR addr=0x20, before the guest starts -- CI runs
  36018431878 and 36081109453, `cross_target_module_names_the_requested_triple_and_runs_there`
  for `linux-x86_64`, 2026-09-24/25). The aarch64 stub was already non-PIE static, and runs
  under `qemu-aarch64` on either host. Reads its trailer from
  `/proc/self/exe` (Linux) or `current_exe()`, runs `_start` with inherited stdio, argv and
  environment; preopens the current directory as fd 3 (what a relative path resolves against,
  `.kb/read-load-streams.md`) under its ABSOLUTE name (`current_dir()`, `.` when unknown or
  not UTF-8) and `/` as fd 4 (covers every absolute path). The absolute name is what lets
  `../x` leave the current directory: the module joins a climbing relative path onto it and
  resolves it through `/`. Exit: the
  `proc_exit` code, 0 on return, **134 after a trap** (what `wasmtime run` answers on Unix,
  `Error: <trap>` on stderr), 1 when the module cannot load (no trailer, refused engine).
- **Fingerprint** `rlnative-abi=2;wasmtime=49.0.0;wasm=gc,function-references,exceptions,tail-call;collector=copying`.
  The stub carries `RLNATIVE-FINGERPRINT=<fingerprint>\0` in its read-only data (kept by a
  `black_box` in `main`); the assembler scans the stub for it and compares with
  `rl_version()`. Bump `rlnative-abi` when the trailer or the C ABI changes (2: `platform`
  and `cpu` joined `rl_precompile`, 2026-09-24), together with `NativeToolchain.ABI`, which
  refuses a shim of another revision before calling it; edit the
  fingerprint together with `rlabi::config`.

## The Java side (`--native -o prog`)

`RontoLispCli.compileRecorded`: `refuseNativeConflicts` first (`--component`, `--no-wasi`,
`--no-gc`, `--host-random`, `--host-fetch`, `--host-boundary`, `--reentrant`,
`--emit-js-glue`, and an `-o` ending in `.wasm`/`.class`/`.jar`/`.war`), then
`NativeToolchain.load().check(target)` -- the host's shim, a trial precompile of the empty
module for the `NativeTarget` (`--native-target`, `--native-cpu`) and the target's stub --
so a host without a shim, an unknown CPU level or a platform without a stub fails before
the front end runs. The module is the ordinary wasm-GC Preview 1 path (`wasmOutput` =
`--native` or `.wasm`); only the write differs:
`NativeExecutable.assemble(stub(platform), precompile(wasm, target))`, written to a
temp file beside `-o`, chmod 0755, atomically moved over it (writing in place fails with
ETXTBSY while the previous output still runs). No `.wasm` or `.cwasm` touches disk.

- **Resources**: `am/ik/rontolisp/native/<linux|macos>-<x86_64|aarch64>/{librlprecomp.so|.dylib, rlrun}`
  on the classpath. `pom.xml` adds `rontolisp-native/target/resources` as a resource
  directory; without the host's pair there `--native` answers "not available for <os>-<arch>".
- **Building it from Maven**: the `native-shims` antrun execution (generate-resources) runs
  `build.sh --maven <rontolisp.native.build> <rontolisp.native.required>`. `build` (default
  `false`, `true` under `-Pnative`) builds the pair when `cargo` is on `PATH` or in
  `~/.cargo/bin`, else warns and goes on; `required` (CI) fails unless the host's pair is
  there afterwards and, on Linux, its stub is static. A static link that fails (no `libc.a`)
  falls back to a dynamic stub with a warning, except under `required`. "Static" is read
  from the ELF program headers (no `PT_INTERP`, `build.sh --is-static`), never from `ldd`:
  ldd calls any foreign-architecture file "not a dynamic executable" and, on the aarch64
  runner, reported the static-pie stub as dynamic (CI run 36008491295, 2026-09-24).
  `build-sh-test.sh` (run by `--test`) pins the check on known static/dynamic executables.
- **Packaging** (decided 2026-09-24 from the sizes below): each `-Pnative` binary carries its
  HOST pair plus the other two release platforms' STUBS (for `--native-target`; +4.1 MB on
  linux-x86_64, +5.4 MB on macOS, uncompressed as native-image stores resources, against a
  ~102 MB binary); the release exec jar carries all three pairs (~11.4 MB compressed; 8.1 MB
  jar -> ~19.5 MB), so `java -jar` compiles `--native` on any of them; the Maven Central jar
  (the `deploy` job) carries none. CI (`ci.yaml`): `native-stubs` (not on pull requests)
  builds each platform's stub (`build.sh --stub <platform>`); each `native-image` leg
  downloads them into `rontolisp-native/target/resources`, builds its pair through
  `-Pnative -Drontolisp.native.required=true`, runs `build.sh --test` (with qemu user mode
  on Linux, `RLNATIVE_REQUIRE_QEMU=1`), runs `NativeOutputE2eTest` and `NativeToolchainTest`
  against the binary (`-Drontolisp.binary`; `required` turns their skips into failures)
  and uploads `native-shims-<platform>`; `release` merges them into
  `rontolisp-native/target/resources` and checks the exec jar lists each `rlrun`. A pull
  request's binary carries its own pair only; its cross-target tests skip.
- **Cache**: `dlopen` needs a file. The shim is extracted to
  `<cache>/native/<sha256 16 hex>/<lib>` via temp file + atomic move; a file of the right
  size there is reused. `<cache>` = `-Drontolisp.native.cache`, else an absolute
  `$XDG_CACHE_HOME/rontolisp`, else `~/Library/Caches/rontolisp` (macOS) /
  `~/.cache/rontolisp`.
- **Fingerprint**: `rl_version()` must be AMONG the NUL-terminated strings following
  `RLNATIVE-FINGERPRINT=` in the stub (`NativeExecutable.stubFingerprints`), else
  `IllegalStateException` before anything is written.
- **native-image**: `reachability-metadata.json` registers `rl_precompile` and `rl_free`
  (the first two downcalls; `rl_version`'s `() -> void*` is Metal's shape), pinned by
  `NativeToolchainTest`; `resource-config.json` includes `am/ik/rontolisp/native/.*` when
  `NativeToolchain` is reachable. `-Pweb` never reaches `cli`.
- **Tests**: `NativeExecutableTest` (layout, marker scan, write), `NativeToolchainTest`,
  `RontoLispCliTest.nativeRefuses...` / `nativeTargetAndCpu...`, and `NativeOutputE2eTest`
  (a ci-spec slice + argv, and a trap's exit status, diffed against
  `wasmtime run --dir . --dir /tmp`; a `../` read from a subdirectory, which wasmtime
  refuses; the baseline and cross-target runs under qemu), which skips without wasmtime or
  qemu on `PATH` or without the resources it needs.

## Traps

- **Build the two in separate cargo invocations.** Cargo unifies a dependency's features
  across the packages of one build, so `cargo build --workspace` puts Cranelift into the
  stub. `build.sh` builds `-p rlprecomp` and `-p rlrun` apart; the stub test uses the
  `release-runner` binary, never a test build (`cargo test` unifies too).
- **Collector**: copying, never DRC (DRC ran `gc.lisp` 9x slower, 12.1 s vs 1.4 s, arm64
  macOS). Homebrew's C API defaults to DRC with no setter -- hence Rust, not the C API.
- **wasmtime >= 47**: 45's copying collector fails the 16 MiB heap pregrow
  (`.kb/wasm-gc-heap-pregrow.md`) on every program. The pin is 49.0.0 (2026-09-24), rustc >= 1.96
  (49's MSRV). 48 replaced `wasmtime-wasi`'s `DirPerms`/`FilePerms` pair with one `FsPerms`
  (read-only or read-write per preopen); the stub asks `ReadWrite` for both.
- **Engine settings are part of the artifact**: a module precompiled with a different GC
  heap reservation is refused ("heap reservation"); pinned by
  `module_precompiled_under_another_config_is_refused_at_start` in
  `rontolisp-native/precomp/tests/stub.rs`.
- **`..` and the sandbox**: every preopen is a cap-std sandbox that refuses a `..` leaving
  it, so a relative `../x` needs fd 3's absolute name above; under `wasmtime run --dir .` it
  still answers the ordinary open errno. `/..` (climbing above the root) is refused, where a
  native program would stay at `/`.
- **macOS signature**: the appended payload is outside the linker's ad-hoc signature
  (`codesign -v` fails strict validation; it still runs from a shell) -- `.todo/944`.
- **ETXTBSY in a multi-threaded test**: exec of a just-written output fails while another
  thread's fork still holds the write descriptor (until that child's exec). `stub.rs`
  retries the spawn; a Java E2E that writes and runs outputs in parallel needs the same.
- **An explicit target turns host detection off.** `Config::target` set (even to the host's
  triple) makes Cranelift start from no ISA flags; unset, it infers the host's. `host` is the
  only CPU level that leaves it unset, so it is refused for another platform.
- **musl is slower, not smaller-and-equal**: a musl stub (2.12 MB) ran `gc.lisp` at 14.0-14.3
  G user cycles against 12.2-12.5 for glibc, dynamic or static (+13-15%, same instruction
  count, pinned to one core, 2026-09-24, Xeon E5-2697A v4). It is the string functions:
  `LD_PRELOAD`ing a `rep movs` `memmove` into the glibc stub made it slower still, and a
  musl stub with mimalloc stayed at 14.0. Static glibc costs size instead (+0.98 MB on
  x86_64); a musl stub with its own `memmove`/`memcpy`/`memset` could have both, at the
  price of owning those routines (`.todo/956`).

## CPU baseline and cross-targets

`rlprecomp::PLATFORMS` owns both: `linux-x86_64`, `linux-aarch64`, `macos-aarch64`,
`macos-x86_64` (no release stub), each with its triple, its baseline flags and its extra
levels. `--native-cpu`: `baseline` (default), `host` (host detection; the host's platform
only), or a level (`x86-64-v2|v3|v4`, x86_64 only). Baselines: x86_64 = SSE2 (no flag);
Linux aarch64 = Armv8.0 (no flag); macOS aarch64 = the Apple M1's set as Cranelift's host
detection reports it (`has_lse`, `has_pauth`, `has_fp16`, `has_dotprod`,
`sign_return_address`, `sign_return_address_with_bkey`). The stub needs neither: wasmtime
checks the module's triple (architecture and OS) and each enabled ISA flag against the
running CPU at load (`Engine::check_compatible_with_isa_flag`), so an output on a CPU that
lacks a feature exits 1 with `compilation setting "has_ssse3" is enabled, but not available
on the host` -- refused, never faulting.

Measured 2026-09-24 (Xeon E5-2697A v4, `rlpack`, min user time of 5 interleaved runs pinned
to one core): the ten bench programs and the `gc` fixture ran within noise at `baseline`,
`x86-64-v2`, `x86-64-v3` and `host` (largest spread: `matmul` 0.84 / 0.84 / 0.82 / 0.82 s,
`gc` 3.47 / 3.53 / 3.43 / 3.48 s); the ci corpus 16.48 / 16.61 / 16.49 / 16.47 s with
identical output, precompile 9.2-9.9 s for all, output 81.67 MB vs 81.63 MB. Host code
differs by <1% of instructions (`lzcnt` for `bsr`, `mulx`, VEX float ops; SSE4.1 `roundsd`
where baseline calls out for floor/ceil). aarch64 (cross-compiled): `.text` of every bench
program and `gc` is byte-identical between the Armv8.0 baseline and all of LSE, FP16,
DotProd, I8MM, PAuth -- those only change atomics and SIMD, which the backend does not emit
without `--simd`. Hence SSE2 / Armv8.0: the floor costs nothing.

Shim size for cross-targets (linux-x86_64, stripped): host backend only 10,242,120 B (3.46 MB
gz); + `x86`/`arm64` 10,961,864 B (+0.72 MB; 3.75 MB gz); wasmtime `all-arch` (adds s390x,
riscv64, Pulley) 12,432,056 B (+2.19 MB; 4.30 MB gz). A local `-Pnative` binary carrying the
x86_64 pair and the aarch64 stub: 108,464,392 B; `--native -o` 0.63 s for a one-liner.

Tests: `precomp/tests/stub.rs` runs a baseline output under `qemu-<arch> -cpu` Opteron_G1
(SSE2, no SSE3) / cortex-a53 (Armv8.0) and checks a `host` one is refused there; checks
every platform's module is its architecture's ELF whose `.wasmtime.engine` names the
triple, and runs the other Linux architecture's under qemu when `build.sh --stub` left its
stub in `target/resources` (or `$RLNATIVE_STUBS`). `NativeOutputE2eTest` does both through
the CLI; `NativeToolchainTest` precompiles for every platform through the shim.

## Numbers

Stub and shim per platform (2026-09-24, wasmtime 49.0.0, stripped; `gz` = gzip -6, what a
jar entry costs): linux-x86_64 shim 10,219,624 B (3.45 MB gz), static stub 2,987,040 B
(1.24 MB gz; dynamic 2,004,424, musl 2,119,184); linux-aarch64 (cross-built) shim 6,689,392 B
(2.67 MB gz), static stub 2,436,232 B (1.08 MB gz; musl 1,839,840); macos-aarch64 from the
spike, shim 5.5 MB, stub 1.7 MB. Static glibc outputs: hello 3,005,768 B, `gc` ~3.2 MB;
`gc` 12.2-12.3 G cycles (= dynamic), `fib` 0.28-0.31 s, hello 0.01-0.02 s / 19 MB RSS. The
cross-built aarch64 static stub ran the ci slice under `qemu-aarch64-static` with output
identical to x86_64's.

2026-09-24, Linux x86_64 (64 cores), rustc 1.98.1, wasmtime 47.0.3:

| | shim | stub |
|---|---|---|
| size (stripped, LTO) | 10.0 MB | 2.0 MB (glibc-dynamic) |

| program | `.wasm` | precompile | output | run | `wasmtime run` |
|---|---|---|---|---|---|
| `fib.lisp` | 2,865 B | 0.05 s | 2.0 MB | 0.30 s | 0.28 s |
| `gc.lisp` | 28,403 B | 0.05 s | 2.2 MB | 3.60 s | 3.54 s |
| `(print "hello")` | | | | 0.02 s, 20 MB RSS | |
| whole ci-spec corpus (581 cases) | 7.6 MB | 11.7 s (90.6 s serial) | 84 MB | 17.6 s | 27.6 s |

Re-measured on **49.0.0** (same day, same host, `rlpack` + stub, three runs): shim 10.2 MB,
stub 2.0 MB; `fib` 0.30-0.31 s (`wasmtime run` 0.27-0.37 s), output 2,042,032 B; `gc`
3.57-3.59 s (`wasmtime run` 3.29-3.48 s), output 2,236,008 B -- the same picture as 47.0.3.

Through the CLI (2026-09-24, same host, load average ~12, `java -jar` exec jar):
`--native -o` costs 0.3 s over `-o x.wasm` (2.96 s vs 2.69 s for `gc.lisp`, JVM start
included); outputs `hello` 2,006,592 B, `fib` 2,025,472 B, `gc` 2,227,640 B; `gc` ran
3.65-3.72 s against `wasmtime run`'s 3.34-3.39 s (+9%, where the stub-only run above was
+2%), `fib` 0.37 s, hello 0.01 s / 20 MB RSS. The Java-assembled `gc` is byte-identical to
`rlpack`'s for the same `.wasm`, so any gap is the stub's, not the assembler's.
`-Pnative` binary (102,500,616 B, carrying the 12.0 MB pair as resources): `--native -o`
takes 0.59 s for hello and 0.72 s for `gc.lisp` (0.80 s on the first call, which extracts
the shim); it also compiles under `env -i` (cache from `user.home`).

The ci corpus printed byte-identical stdout (4,722 lines) under the stub (`.` + `/`, before
fd 3 carried the absolute name) and `wasmtime run --dir . --dir /tmp`. `parallel-compilation` is deterministic: serial and
parallel outputs were byte-identical. A precompiled module is ~11x its `.wasm`.
arm64 macOS spike numbers (2026-09-24): shim 5.5 MB, stub 1.7 MB, `gc.lisp` 1.32 s (=
`wasmtime run`), hello starts < 10 ms.

Pinned by `rontolisp-native/precomp/tests/stub.rs` (fixtures compiled by rontolisp, run from
`<tmp>/cwd` beside `<tmp>/up.txt`; stdout and exit status equal to `wasmtime run`'s with the
stub's preopens, recorded by `gen-fixtures.sh`) and the
`rlabi` / `rlprecomp` unit tests.
