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
  `build.sh --maven <rontolisp.native.build> <rontolisp.native.required>`. `build` (default
  `false`, `true` under `-Pnative`) builds the pair when `cargo` is on `PATH` or in
  `~/.cargo/bin`, else warns and goes on; `required` (CI) fails unless the host's pair is
  there afterwards and, on Linux, its stub is static. Without the musl target (no rustup)
  the stub falls back to linking glibc dynamically with a warning, except under `required`.
  "Static" is read
  from the ELF program headers (no `PT_INTERP`, `build.sh --is-static`), never from `ldd`:
  ldd calls any foreign-architecture file "not a dynamic executable" and, on the aarch64
  runner, reported the static-pie stub as dynamic (CI run 36008491295, 2026-09-24).
  `build-sh-test.sh` (run by `--test`) pins the check on known static/dynamic executables.
- **Packaging** (decided 2026-09-24 from the sizes below): each `-Pnative` binary carries its
  HOST pair plus the other two release platforms' STUBS (for `--native-target`; +4.1 MB on
  linux-x86_64, +5.4 MB on macOS with the static-glibc stubs, which the musl ones undercut by
  0.83 MB (x86_64) and 0.60 MB (aarch64); uncompressed as native-image stores resources, against a
  ~102 MB binary); the release exec jar carries all three pairs (~11.4 MB compressed; 8.1 MB
  jar -> ~19.5 MB), so `java -jar` compiles `--native` on any of them; the Maven Central jar
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
- **ETXTBSY in a multi-threaded test**: exec of a just-written output fails while another
  thread's fork still holds the write descriptor (until that child's exec). `stub.rs`
  retries the spawn; a Java E2E that writes and runs outputs in parallel needs the same.
- **An explicit target turns host detection off.** `Config::target` set (even to the host's
  triple) makes Cranelift start from no ISA flags; unset, it infers the host's. `host` is the
  only CPU level that leaves it unset, so it is refused for another platform.
- **musl: small, but not with its own `memcpy`/`memmove`.** Static glibc cost 0.83 MB
  (x86_64; 0.60 MB aarch64) over musl, and no linker flag wins it back: of the x86_64 stub's
  +700 KB `.text` / +208 KB `.rodata` over the dynamic one, 678 KB / 89 KB are whole `libc.a`
  members glibc's own static startup, stdio, locale, `dlopen` (NSS, gconv) and IFUNC
  variants pull in (`vfscanf` 37 KB, `gconv_simple` 31 KB, `malloc` 26 KB, `vf[w]printf`
  38 KB, `dl-*`, `strto*_l`, every `str*`/`mem*` SSE2/AVX2/EVEX variant; `C-ctype` 57 KB
  of `.rodata`), measured from the link map 2026-09-25. musl's cost was speed: the copying
  collector copies each surviving object with `copy_within` (`memmove`, most under 64 bytes),
  and musl's x86_64 `memcpy` starts every copy with `rep movsq` -- 11.7% of `gc.lisp`'s
  cycles as one symbol, 13.5-14.0 G user cycles against 11.6-11.9 for static glibc in the
  same interleaved runs (+16-18%, same instruction count; 2026-09-25 at four codegen units, pinned to one core, Xeon
  E5-2697A v4; at one unit 2026-09-24 it was +13-15%, and mimalloc did not move it). So
  the x86_64 stub defines `memcpy`/`memmove`/`memset` itself (`runner/src/memfns.rs`, module
  assembly: every load before any store up to 128 bytes, a 64-byte loop in the safe
  direction above, `rep movsb`/`stosb` from 2 KiB forwards; SSE2 only), which keeps
  `libc.a`'s members out of the link. Measured 2026-09-25 against the static-glibc stub, five
  interleaved runs: `gc` 10.96-11.25 G cycles against 11.25-11.38 (-2.5%), 3.16-3.38 s
  against 3.27-3.52 s; `hash` -1.2%; `bignum`, `clos`, `fib`, `list`, `mandelbrot`,
  `matmul`, `sieve`, `sort`, `string` within 0.5%; hello 0.01 s / 18 MB RSS. On aarch64
  musl's `memcpy`/`memset` are Arm's optimized-routines assembly, but its `memmove` is C
  that tests for overlap and then calls `memcpy`: `gc.lisp` makes 62 M `memmove` calls,
  +396 M user instructions (+1.4%) over static glibc's `__memmove_sve`. Measured
  2026-09-25 on hardware (NVIDIA GB10, Cortex-X925 + Cortex-A725; min user cycles of five
  interleaved runs pinned to one core, each core type's own PMU): musl `gc` +1.2% on the
  X925, +3.3% on the A725 (6.20-6.23 G against 6.01-6.02 G), `hash` +1.6% / -0.3%, the
  rest within 0.5%. So aarch64 defines `memcpy`/`memmove` too (the same shape in
  `ldp`/`stp` of q registers, no `rep` tier; Armv8.0 only) and keeps musl's `memset`:
  `gc` 5.77-5.86 G cycles against glibc's 6.01-6.02 (-4.0%) on the A725 and +1.2% on the
  X925 (4.615-4.641 against 4.560-4.683: musl's cycles at 2.9% fewer instructions), `hash`
  -2.9% / -0.4%, the other nine within 0.5% except `mandelbrot` +0.9% on the A725 (it calls
  neither routine: noise). Stub size unchanged (1,971,136 B; static glibc 2,501,768 B,
  +0.53 MB). The routines' tests (`memfns::tests`, in `build.sh --test`, native on either
  architecture) cover every length to 300 and around the thresholds, every alignment and
  every overlap distance to 70 either way; a load moved after a store, or a backwards move sent forwards,
  fails them.
- **The stub is not built as one codegen unit.** Under `codegen-units = 1` (what `release`
  keeps for the shim) LLVM leaves wasmtime's `GcHeap::index::<VMCopyingHeader>` out of line
  in the copying collector's `forward` (11% of `gc.lisp`'s cycles as its own symbol): 38.0 G
  user instructions against 33.9 G at 2, 4, 8, 16 or 32 units, the count `wasmtime run`
  (the 49.0.0 release CLI) retires on the same module (measured 2026-09-25, x86_64; the
  out-of-line copy is in the aarch64 stub too, by its symbol). It was the whole 7-9% gap to
  `wasmtime run`, not the feature set, the allocator or the precompiled code: a throwaway
  runner with wasmtime's default features ran the stub's module at the stub's count, and
  one with the minimal features at 16 units at the CLI's. `release-runner` sets 4, the
  smallest stub of those (x86_64 +69 KB / +26 KB gz over one unit, aarch64 +65 KB / +21 KB
  gz; 16 was +149 KB); pinned by `runner_profile_has_more_than_one_codegen_unit` (`rlabi`).
  Other programs: `hash` -9.5% instructions, `list` / `sort` -1%, the other bench programs
  unchanged. The shim stays at one unit: at four, precompiling `llm.lisp` took 2% more
  instructions.

## The network runner

A program that fetches (`.kb/fetch-http.md`, "--native") imports `rlhttp`, which the runner answers
with an HTTP/1.1 client over rustls + ring (`runner/src/http`, the cargo feature `net`). That client
costs more than the rest of the stub's growth combined, so it is a SECOND stub, `rlrun-net`, built
from the same crate with `--features net`, and only a module that imports `rlhttp` starts with it:
`RontoLispCli` asks `WasmLispCompiler.importModules` of the FINISHED module (after the tree shaker,
so a fetch nothing reaches picks the plain stub) and `NativeToolchain.stub(platform, network)`
loads `rlrun` or `rlrun-net`. Every other output starts with `rlrun`, whose one new start-up step is
the scan of the module's imports for `rlhttp`.

Measured 2026-09-25 (stripped static musl, four codegen units, gz = gzip -6):

| | `rlrun` | `rlrun-net` |
|---|---|---|
| linux-x86_64 | 2,094,104 B (0.85 MB gz) | 3,261,528 B (1.48 MB gz) |
| linux-aarch64 (cross-built) | 1,971,136 B (0.83 MB gz) | 2,888,712 B (1.44 MB gz) |
| macos-aarch64 (2026-09-26, M4 Max, rustc 1.96.0) | 1,799,168 B (0.77 MB gz) | 2,646,736 B (1.32 MB gz) |

`rlrun` measured the same size as before the feature existed. `profile.release-runner.package.<crate>` builds
rustls, ring, rustls-webpki, untrusted and rustls-pki-types at `opt-level = "z"`: without it
`rlrun-net` is 3,519,576 B (1.59 MB gz) on x86_64, so it saves 258,048 B, and a 256 MiB HTTPS
download through an output took 10.3-11.4 s user with it and 11.2-11.4 s without (the module's own
work dominates, `.kb/fetch-http.md`, "Throughput"). On macos-aarch64 the network host costs
847,568 B (+0.55 MB gz), less than on Linux; there `FetchSpecE2eTest` (native leg under
`-Drontolisp.native.required=true`) and `NativeOutputE2eTest` pass on the JVM compiler (2026-09-26).

- **Why one TLS stack everywhere, and why rustls.** A static musl stub cannot `dlopen` the system's
  TLS library, so Linux needs its own; macOS could use its system stack (Security.framework /
  CFNetwork, through the `rlobjc` machinery) at no size cost, but that is a second transport with
  its own trust store, errors and HTTP semantics, saving ~1 MB on fetching programs only. ring,
  not aws-lc-rs (no cmake, the smaller of the two); TLS 1.2 kept; roots from `webpki-roots`
  (Mozilla's, the set wasmtime's `wasi:http` trusts), replaced by `SSL_CERT_FILE`'s bundle when set.
- **Why a second stub rather than a bigger one.** Every output would otherwise grow by 1.17 MB
  (x86_64) for a feature most programs never use; the tool pays instead, once: each platform ships
  two stubs (a `-Pnative` binary carries the other platforms' `rlrun-net` too, +3.3 MB and +2.9 MB
  uncompressed for the two Linux ones; the exec jar +1.48 and +1.44 MB gz).
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
  16 KiB page (+16,448 B of stub). No segment is ever added: dyld's binding opcodes and
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
- Numbers (2026-09-25, Apple silicon, `rlpack`): `gc` output 1,864,323 B, 1.32-1.33 s (the
  first run of a fresh file 1.71 s: the kernel validates the signature on first exec),
  `wasmtime run` 1.37-1.42 s; `fib` 0.10 s / 20 MB RSS.

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
stub in `target/resources` (or `$RLNATIVE_STUBS`). Each emulated run is guarded by a probe:
the bare target stub must exit 1 naming the missing module under that qemu here, else the
runs for that architecture are skipped -- `RLNATIVE_REQUIRE_QEMU` still requires qemu to
be installed, it only excuses a qemu that cannot run (the 8.2.2 `qemu-x86_64` maps bug
above skips the cross-x86_64 run on aarch64 hosts; the ELF-shape assertions still run
everywhere, and every stub is runtime-tested natively on its own arch by the fixture
tests). `NativeOutputE2eTest` does both through
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

2026-09-25, after the Mach-O payload change and at four codegen units (Traps): static stub
linux-x86_64 2,928,288 B (1.22 MB gz), linux-aarch64 2,501,768 B (1.10 MB gz); outputs hello
2,947,016 B, `fib` 2,965,896 B, `gc` 3,159,872 B. Pinned to one core, load average < 3, five
interleaved runs: `gc` 3.25-3.28 s against `wasmtime run` 3.27-3.33 s (one unit:
3.55-3.57 s), `hash` 0.74-0.76 s against 0.75-0.86 s (one unit: 0.81-0.83 s), `fib`
0.26 s = `wasmtime run`; hello 0.01 s / 19 MB RSS.

2026-09-25, static musl (Traps, "musl"): stub linux-x86_64 2,094,104 B (0.85 MB gz;
-834,184 B), linux-aarch64 (cross-built) 1,905,600 B (0.83 MB gz; -596,168 B); outputs hello
2,112,832 B, `gc` 2,325,688 B. `gc` 3.16-3.38 s against 3.27-3.52 s for the static-glibc
stub in the same interleaved runs (machine busier than above).

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
`rlpack`'s for the same `.wasm`, so any gap is the stub's, not the assembler's. The gap
was the stub's single codegen unit (Traps); closed 2026-09-25.
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
