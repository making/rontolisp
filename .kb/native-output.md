# Native-executable output: the wasmtime shim and the runner stub

**Invariant**: a Linux native output is `stub ++ module ++ u64-le(module length) ++ "RLNATIVE"`;
a macOS one is the stub's Mach-O image with the module INSIDE it (section
`__RLPAYLOAD,__payload` = `u64-le(length) ++ "RLNATIVE" ++ module`) and re-signed ad hoc
over the whole file ("macOS: the module inside the signed image"). `module` is the wasm-GC
backend's Preview 1 module precompiled by the shim, `stub` the runner, and the shim's
`rlprecomp::assemble` is the ONE implementation of both layouts (Java calls it through
`rl_assemble`). Shim and stub build their engine from ONE function (`rlabi::config`) of ONE
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
  `rl_assemble(stub, stub_len, module, module_len, &out, &out_len) -> i32` (same codes and
  buffer contract: the executable, or the message), `rl_version() -> const char*` (static
  `FINGERPRINT`). Features `cranelift` +
  `parallel-compilation`, plus Cranelift's `x86` and `arm64` backends through a direct
  `cranelift-codegen` dependency whose features unify into wasmtime's (its version moves
  with the wasmtime pin). The release profile keeps
  `panic = unwind` and `rl_precompile` catches at the boundary: it runs inside a JVM.
  `install_name @rpath/librlprecomp.dylib` / `soname librlprecomp.so` via `precomp/build.rs`.
- **Stub** `rlrun`: runtime-only wasmtime (`runtime std gc gc-copying`, no Cranelift) +
  `wasmtime-wasi` `p1`; profile `release-runner` (`panic = abort`). On Linux it links musl
  STATICALLY (target `<arch>-unknown-linux-musl`, which `build.sh` adds through rustup when
  missing, `-C relocation-model=static`; the explicit `--target` keeps the flag off build
  scripts; binary at `target/<triple>/release-runner/rlrun`), and brings its own
  `memcpy`/`memmove` (and on x86_64 `memset`; `runner/src/memfns.rs`; Traps, "musl"): an output has no
  glibc floor (checked 2026-09-24 with static glibc and 2026-09-25 with musl in
  `alpine:3.20`, `centos:7` = glibc 2.17 and `busybox`, where the dynamic stub failed on
  `GLIBC_2.34` / `libgcc_s.so.1`). The module is still precompiled for the `-gnu` triple:
  wasmtime compares only architecture and OS. The relocation model keeps both Linux stubs
  the same non-PIE static shape (the default is static-pie). It does NOT fix
  `qemu-x86_64` on an aarch64 host (measured 2026-09-25: a non-PIE EXEC stub crashes
  identically, and so does a C program that only opens `/proc/self/maps`): that QEMU
  (8.2.2) dies with an internal SIGSEGV, MAPERR addr=0x20, as soon as the guest opens
  `/proc/self/maps` -- Rust's startup guard setup
  (`std/.../stack_overflow.rs::install_main_guard_linux`) does on every glibc binary, through
  glibc's `pthread_getattr_np` (musl's does not read the file; whether the musl stub now runs
  there is unmeasured) -- while `qemu-aarch64` on either host and `qemu-x86_64` on an x86_64
  host emulate the same open fine (CI run 36086331612,
  `cross_target_module_names_the_requested_triple_and_runs_there` for `linux-x86_64`).
  The emulated runs for an architecture whose qemu cannot run the bare target stub are
  skipped by a probe in `precomp/tests/stub.rs`, never by the link shape. Reads its trailer from
  `/proc/self/exe` (Linux); on macOS takes the module from its own mapped payload section
  (`getsectiondata`, no read of the file). Runs `_start` with inherited stdio, argv and
  environment; preopens the current directory as fd 3 (what a relative path resolves against,
  `.kb/read-load-streams.md`) under its ABSOLUTE name (`current_dir()`, `.` when unknown or
  not UTF-8) and `/` as fd 4 (covers every absolute path). The absolute name is what lets
  `../x` leave the current directory: the module joins a climbing relative path onto it and
  resolves it through `/`. Exit: the
  `proc_exit` code, 0 on return, **134 after a trap** (what `wasmtime run` answers on Unix,
  `Error: <trap>` on stderr), 1 when the module cannot load (no trailer, refused engine).
  A module importing from `rlobjc` (an `objc:`/`appkit:`/`metal:`/`scene:` program) also gets the
  Objective-C host (`runner/src/objc`, macOS aarch64 only -- elsewhere it exits 1 naming the
  platform) and runs ON thread 0, `sleep` turning the event loop ([objc.md](objc.md), "--native").
  A module importing from `rlhttp` (a program that fetches) needs the NETWORK runner, `rlrun-net`
  ("The network runner" below); the plain stub exits 1 naming it.
  Not part of the fingerprint: the host is linked per module import, and a module is only ever
  assembled with the stub of its own build.
- **Fingerprint** `rlnative-abi=3;wasmtime=49.0.0;wasm=gc,function-references,exceptions,tail-call;collector=copying`.
  The stub carries `RLNATIVE-FINGERPRINT=<fingerprint>\0` in its read-only data (kept by a
  `black_box` in `main`); the assembler scans the stub for it and compares with
  `rl_version()`. Bump `rlnative-abi` when the trailer or the C ABI changes (2: `platform`
  and `cpu` joined `rl_precompile`, 2026-09-24; 3: `rl_assemble` and the macOS embedded payload,
  2026-09-25), together with `NativeToolchain.ABI`, which
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
`--native` or `.wasm`), plus the runner's fetch lowering when the program fetches; only the write
differs: `NativeToolchain.assemble(stub(platform, network), precompile(wasm, target))`
(`rl_assemble`; `network` = the finished module imports `rlhttp`), written to a
temp file beside `-o`, chmod 0755, atomically moved over it (writing in place fails with
ETXTBSY while the previous output still runs). No `.wasm` or `.cwasm` touches disk.

- **Resources**: `am/ik/rontolisp/native/<linux|macos>-<x86_64|aarch64>/{librlprecomp.so|.dylib, rlrun, rlrun-net}`
  on the classpath. `pom.xml` adds `rontolisp-native/target/resources` as a resource
  directory; without the host's pair there `--native` answers "not available for <os>-<arch>".
- **Building it from Maven**: the `native-shims` antrun execution (generate-resources) runs
  `build.sh --maven <rontolisp.native.build> <rontolisp.native.required>` on every Unix build,
  whatever the two properties say (until 2026-10-23 only when one was `true`, so the stale
  rebuild below never ran under a plain `./mvnw test`). `build` (default
  `false`, `true` under `-Pnative`) builds the pair when `cargo` is on `PATH` or in
  `~/.cargo/bin`, else warns and goes on; `required` (CI) fails unless the host's pair is
  there afterwards and, on Linux, its stub is static. Without the musl target (no rustup)
  the stub falls back to linking glibc dynamically with a warning, except under `required`.
  A host pair already in `target/resources` and older than a file under `Cargo.toml`,
  `Cargo.lock`, `abi/`, `precomp/` or `runner/` is rebuilt under `build=false` too (make's rule
  on mtimes; without cargo, a warning): a checkout's pair of 2026-10-08 sent every
  octet-vector request body empty, the runner having learned `octets` on 10-09, and failed the
  native leg of `FetchSpecE2eTest` on 2026-10-23 while CI passed. A pair CI downloads is newer
  than the checkout, so it is never rebuilt. "Static" is read
  from the ELF program headers (no `PT_INTERP`, `build.sh --is-static`), never from `ldd`:
  ldd calls any foreign-architecture file "not a dynamic executable" and, on the aarch64
  runner, reported the static-pie stub as dynamic (CI run 36008491295, 2026-09-24).
  `build-sh-test.sh` (run by `--test`) pins the check on known static/dynamic executables.
- **Packaging** (decided 2026-09-24): each `-Pnative` binary carries its
  HOST pair plus the other two release platforms' STUBS (for `--native-target`; uncompressed, as
  native-image stores resources); the release exec jar carries all three pairs, so `java -jar`
  compiles `--native` on any of them; the Maven Central jar
  (the `deploy` job) carries none. CI (`ci.yaml`): `native-stubs` (not on pull requests)
  builds each platform's stub (`build.sh --stub <platform>`); each `native-image` leg
  downloads them into `rontolisp-native/target/resources`, builds its pair through
  `-Pnative -Drontolisp.native.required=true`, runs `build.sh --test` (with qemu user mode
  on Linux, `RLNATIVE_REQUIRE_QEMU=1`), runs `NativeOutputE2eTest`, `NativeToolchainTest` and
  `FetchSpecE2eTest` against the binary (`-Drontolisp.binary`; `required` turns their skips
  into failures) and uploads `native-shims-<platform>`; `release` merges them into
  `rontolisp-native/target/resources` and checks the exec jar lists each `rlrun` and
  `rlrun-net`. Every platform ships both stubs since 2026-09-25 ("The network runner"). A pull
  request's binary carries its own pair only; its cross-target tests skip.
- **Cache**: `dlopen` needs a file. The shim is extracted to
  `<cache>/native/<sha256 16 hex>/<lib>` via temp file + atomic move; a file of the right
  size there is reused. `<cache>` = `-Drontolisp.native.cache`, else an absolute
  `$XDG_CACHE_HOME/rontolisp`, else `~/Library/Caches/rontolisp` (macOS) /
  `~/.cache/rontolisp`.
- **Fingerprint**: `rl_version()` must be AMONG the NUL-terminated strings following
  `RLNATIVE-FINGERPRINT=` in the stub (`NativeExecutable.stubFingerprints`), else
  `IllegalStateException` before anything is written.
- **native-image**: `reachability-metadata.json` registers `rl_precompile`, `rl_free` and
  `rl_assemble` (the first three downcalls; `rl_version`'s `() -> void*` is Metal's shape), pinned by
  `NativeToolchainTest`; `resource-config.json` includes `am/ik/rontolisp/native/.*` when
  `NativeToolchain` is reachable. `-Pweb` never reaches `cli`.
- **Tests**: `NativeExecutableTest` (marker scan, write), `NativeToolchainTest` (incl.
  `rl_assemble`'s append and refusal),
  `RontoLispCliTest.nativeRefuses...` / `nativeTargetAndCpu...`, and `NativeOutputE2eTest`
  (a ci-spec slice + argv, and a trap's exit status, diffed against
  `wasmtime run --dir . --dir /tmp`; a `../` read from a subdirectory, which wasmtime
  refuses; the baseline and cross-target runs under qemu), which skips without wasmtime or
  qemu on `PATH` or without the resources it needs; on macOS it also runs
  `codesign --verify --strict` on an output.

## Traps

- **Build the two in separate cargo invocations.** Cargo unifies a dependency's features
  across the packages of one build, so `cargo build --workspace` puts Cranelift into the
  stub. `build.sh` builds `-p rlprecomp` and `-p rlrun` apart; the stub test uses the
  `release-runner` binary, never a test build (`cargo test` unifies too).
- **Collector**: copying, never DRC (DRC runs `gc.lisp` many times slower, arm64
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
- **ETXTBSY in a multi-threaded test**: exec of a just-written output fails while another
  thread's fork still holds the write descriptor (until that child's exec). `stub.rs`
  retries the spawn; a Java E2E that writes and runs outputs in parallel needs the same.
- **An explicit target turns host detection off.** `Config::target` set (even to the host's
  triple) makes Cranelift start from no ISA flags; unset, it infers the host's. `host` is the
  only CPU level that leaves it unset, so it is refused for another platform.
- **musl: small, but not with its own `memcpy`/`memmove`.** Static glibc costs markedly more
  than musl, and no linker flag wins it back: glibc's own static startup, stdio, locale,
  `dlopen` (NSS, gconv) and IFUNC variants pull whole `libc.a` members into the link
  (`vfscanf`, `gconv_simple`, `malloc`, `vf[w]printf`, `dl-*`, `strto*_l`, every
  `str*`/`mem*` SSE2/AVX2/EVEX variant, `C-ctype`), measured from the link map 2026-09-25.
  musl's cost was speed: the copying
  collector copies each surviving object with `copy_within` (`memmove`, mostly short),
  and musl's x86_64 `memcpy` starts every copy with `rep movsq`, a measurable share of
  `gc.lisp`'s cycles against static glibc in the same interleaved runs, at the same
  instruction count (mimalloc does not move it). So
  the x86_64 stub defines `memcpy`/`memmove`/`memset` itself (`runner/src/memfns.rs`, module
  assembly: every load before any store up to 128 bytes, a 64-byte loop in the safe
  direction above, `rep movsb`/`stosb` from 2 KiB forwards; SSE2 only), which keeps
  `libc.a`'s members out of the link; against the static-glibc stub in interleaved runs,
  `gc` and the other bench programs land within noise or better. On aarch64
  musl's `memcpy`/`memset` are Arm's optimized-routines assembly, but its `memmove` is C
  that tests for overlap and then calls `memcpy`, which `gc.lisp` exercises heavily; measured
  on hardware, the hand-written routines land within noise of glibc's per program and beat
  its `memmove` where it counts. So aarch64 defines `memcpy`/`memmove` too (the same shape in
  `ldp`/`stp` of q registers, no `rep` tier; Armv8.0 only) and keeps musl's `memset`.
  The routines' tests (`memfns::tests`, in `build.sh --test`, native on either
  architecture) cover every length to 300 and around the thresholds, every alignment and
  every overlap distance to 70 either way; a load moved after a store, or a backwards move sent forwards,
  fails them.
- **The stub is not built as one codegen unit.** Under `codegen-units = 1` (what `release`
  keeps for the shim) LLVM leaves wasmtime's `GcHeap::index::<VMCopyingHeader>` out of line
  in the copying collector's `forward`, a measurable share of `gc.lisp`'s cycles as its own
  symbol (measured 2026-09-25, x86_64; the
  out-of-line copy is in the aarch64 stub too, by its symbol). It was the whole gap to
  `wasmtime run`, not the feature set, the allocator or the precompiled code: a throwaway
  runner with wasmtime's default features ran the stub's module at the stub's count, and
  one with the minimal features at 16 units at the CLI's. `release-runner` sets 4; pinned by
  `runner_profile_has_more_than_one_codegen_unit` (`rlabi`).
  The shim stays at one unit.

## The network runner

A program that fetches (`.kb/fetch-http.md`, "--native") imports `rlhttp`, which the runner answers
with an HTTP/1.1 client over rustls + ring (`runner/src/http`, the cargo feature `net`). That client
costs more than the rest of the stub's growth combined, so it is a SECOND stub, `rlrun-net`, built
from the same crate with `--features net`, and only a module that imports `rlhttp` starts with it:
`RontoLispCli` asks `WasmLispCompiler.importModules` of the FINISHED module (after the tree shaker,
so a fetch nothing reaches picks the plain stub) and `NativeToolchain.stub(platform, network)`
loads `rlrun` or `rlrun-net`. Every other output starts with `rlrun`, whose one new start-up step is
the scan of the module's imports for `rlhttp`.

`profile.release-runner.package.<crate>` builds
rustls, ring, rustls-webpki, untrusted and rustls-pki-types at `opt-level = "z"`, which is what
keeps `rlrun-net`'s size down; a large HTTPS download through an output is dominated by the
module's own work, not the TLS stack (`.kb/fetch-http.md`, "Throughput"). On macos-aarch64 the
network host costs less than it does on Linux; there `FetchSpecE2eTest` (native leg under
`-Drontolisp.native.required=true`) and `NativeOutputE2eTest` pass on the JVM compiler (2026-09-26).

- **Why one TLS stack everywhere, and why rustls.** A static musl stub cannot `dlopen` the system's
  TLS library, so Linux needs its own; macOS could use its system stack (Security.framework /
  CFNetwork, through the `rlobjc` machinery) at no size cost, but that is a second transport with
  its own trust store, errors and HTTP semantics, saving size on fetching programs only. ring,
  not aws-lc-rs (no cmake, the smaller of the two); TLS 1.2 kept; roots from `webpki-roots`
  (Mozilla's, the set wasmtime's `wasi:http` trusts), replaced by `SSL_CERT_FILE`'s bundle when set.
- **Why a second stub rather than a bigger one.** Every output would otherwise grow for a feature
  most programs never use; the tool pays instead, once: each platform ships
  two stubs (a `-Pnative` binary carries the other platforms' `rlrun-net` too, and the exec jar
  carries them gzipped).
- **Building both**: `build.sh` builds the stub twice (two cargo invocations write one binary path,
  so `rlrun-net` is copied out before `rlrun` is built over it); `--maven ... true` requires both,
  static; `--test` runs the workspace tests `--features rlrun/net`, TLS included
  (`http::wire::tests` against a local rustls origin with the test root in
  `runner/src/http/testdata`).
- **The start-up path of a fetching output** adds nothing until its first `start`: the TLS
  configuration (and the roots) is built on the first HTTPS request.
- Tests: `NativeFetchTest` (a program that never fetches carries the same module for every
  platform as for a `.wasm`; a fetching one imports exactly `rlhttp.start/head/readResponseBody`;
  on Linux the output starts with the stub its imports need), `NativeToolchainTest` (the network
  runner pairs with the same shim), the Rust `http::*` tests and the native leg of
  `FetchSpecE2eTest`. A fetching `linux-aarch64` output ran the fetch program and HTTPS to
  `example.com` under `qemu-aarch64-static -cpu cortex-a53` (by hand, 2026-09-25).

## macOS: the module inside the signed image

A payload appended after a Mach-O stub is outside the linker's ad-hoc signature:
`codesign -v` reported "main executable failed strict validation" (it still ran from a shell,
2026-09-24, with and without `com.apple.quarantine`). So the module goes INSIDE the image and
the image is re-signed, in pure Rust (`precomp/src/macho.rs`), with no `codesign` on the
compiling host -- a Linux host writes `--native-target macos-aarch64` outputs too. Rust, not
Java: `rlpack` and `stub.rs` need the same assembler, and one implementation serves both.

- **The stub reserves the room**: `runner/src/main.rs` puts a 16-byte `#[used]` static
  (`rlabi::payload::EMPTY_SECTION`, a header naming an empty module; not all zeros, which
  the linker may turn into zero-fill) in `__RLPAYLOAD,__payload`; `runner/build.rs` passes
  `-segprot __RLPAYLOAD r r`. ld (1230.1) lays the segment out LAST before `__LINKEDIT`, one
  16 KiB page. No segment is ever added: dyld's binding opcodes and
  chained fixups name segments by index, and the linker already counted this one.
- **Embedding** (`macho::embed`): writes `header ++ module` at the section, grows the
  segment to a 16 KiB multiple, moves `__LINKEDIT` (fileoff and vmaddr) behind it and adds
  the delta to every file offset that points into it (`LC_SYMTAB`, `LC_DYSYMTAB`,
  `LC_DYLD_INFO[_ONLY]`, the `linkedit_data_command`s). A load command in neither of its
  two lists (offset-bearing / offset-free) is REFUSED rather than guessed about, as is a
  stub whose last two segments are not `__RLPAYLOAD`, `__LINKEDIT`.
- **Signing**: drops the linker's signature and writes the shape ld writes (read off the
  stub 2026-09-25): a `SuperBlob` with ONE `CodeDirectory` v0x20400, flags
  `adhoc | linker-signed`, no special slots, SHA-256 of each 4 KiB page up to the signature
  (16-aligned, the file's end), `execSeg` = `__TEXT` with `MAIN_BINARY`, identifier kept
  from the stub's (`rlrun-<hash>`). `stub.rs` checks every page hash on any host holding the
  macOS stub; on macOS, `codesign --verify --strict` accepts the output and refuses it
  after one module byte changes. `spctl -a` still rejects it: ad hoc is not notarized, and
  notarization needs a Developer ID -- out of scope.
- **Limits**: Mach-O file offsets are 32-bit; an output past 4 GiB is refused.
- The first run of a fresh output is slower than the rest: the kernel validates the signature
  on first exec.

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

The bench programs and the `gc` fixture run within noise at `baseline`,
`x86-64-v2`, `x86-64-v3` and `host`, the ci corpus with identical output. Host code
differs only at the instruction level (`lzcnt` for `bsr`, `mulx`, VEX float ops; SSE4.1 `roundsd`
where baseline calls out for floor/ceil). aarch64 (cross-compiled): the code of every bench
program and `gc` is byte-identical between the Armv8.0 baseline and all of LSE, FP16,
DotProd, I8MM, PAuth -- those only change atomics and SIMD, which the backend does not emit
without `--simd`. Hence SSE2 / Armv8.0: the floor costs nothing.

Tests: `precomp/tests/stub.rs` runs a baseline output under `qemu-<arch> -cpu` Opteron_G1
(SSE2, no SSE3) / cortex-a53 (Armv8.0) and checks a `host` one is refused there; checks
every platform's module is its architecture's ELF whose `.wasmtime.engine` names the
triple, and runs the other Linux architecture's under qemu when `build.sh --stub` left its
stub in `target/resources` (or `$RLNATIVE_STUBS`). Each emulated run is guarded by a probe:
the bare target stub must exit 1 naming the missing module under that qemu here, else the
runs for that architecture are skipped -- `RLNATIVE_REQUIRE_QEMU` still requires qemu to
be installed, it only excuses a qemu that cannot run (the 8.2.2 `qemu-x86_64` maps bug
above skips the cross-x86_64 run on aarch64 hosts; the ELF-shape assertions still run
everywhere, and every stub is runtime-tested natively on its own arch by the fixture
tests). `NativeOutputE2eTest` does both through
the CLI; `NativeToolchainTest` precompiles for every platform through the shim.

## Numbers

The cross-built aarch64 static stub ran the ci slice under `qemu-aarch64-static` with output
identical to x86_64's. The Java-assembled output is byte-identical to `rlpack`'s for the same
`.wasm`, so any run-time difference is the stub's, not the assembler's. `parallel-compilation` is
deterministic: serial and parallel outputs are byte-identical. The ci corpus printed
byte-identical stdout under the stub (`.` + `/`, before fd 3 carried the absolute name) and
`wasmtime run --dir . --dir /tmp`. A `-Pnative` binary compiles `--native -o` as well, and under
`env -i` (cache from `user.home`).

Pinned by `rontolisp-native/precomp/tests/stub.rs` (fixtures compiled by rontolisp, run from
`<tmp>/cwd` beside `<tmp>/up.txt`; stdout and exit status equal to `wasmtime run`'s with the
stub's preopens, recorded by `gen-fixtures.sh`) and the
`rlabi` / `rlprecomp` unit tests.
