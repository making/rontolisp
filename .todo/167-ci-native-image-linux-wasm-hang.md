# CI hang: Linux native-image binary emits a WASM that wasmtime cannot finish

The `native-image` job in `.github/workflows/ci.yaml` has been red since a push
just after `6645c6d` (last green) on `develop`. The `test` and
`native-image (macos-latest, rontolisp-darwin-arm64)` jobs are green; only the
two Linux native-image jobs fail, and the way they fail points at the WASM
Preview 1 backend of `CiSpecE2eTest`.

The macOS half of the same run passes with the same source, the same
`wasmtime 47.0.2`, and the same `ci-spec.yaml` corpus, so the divergence is
between the Linux and macOS **GraalVM `native-image` outputs**, not between
wasmtime installs.

## Precisely what is observed

`CiSpecE2eTest` per-sub-exec logs (already committed) narrow the failure to
one exec:

    [CiSpecE2eTest] starting backend WASM
    [CiSpecE2eTest]   > compile-wasm [.../rontolisp, ci-program.lisp, -o, test.wasm]
    [CiSpecE2eTest]   < compile-wasm ok in 243 ms (0 lines)
    [CiSpecE2eTest]   > run-wasm [wasmtime, --wasm, gc, --wasm, exceptions=y, --dir, ., test.wasm]
    ##[error]The runner has received a shutdown signal.

So the Linux native binary **successfully emits a `test.wasm`**; it is the
subsequent `wasmtime` run on that `.wasm` that hangs long enough that the
GitHub Actions runner is torn down before the 300 s Java-side timeout in
`CiSpecE2eTest.exec()` fires. amd64 dies at ~25 s in the run step; arm64
lasted ~271 s and was still hung when the strategy cancelled it as a peer of
the amd64 failure. The macOS binary runs the same corpus through the same
wasmtime invocation and finishes in about 200 ms locally.

`WasmLispCompilerIntegrationTest` (~757 small WASM programs) is **green on
CI** on the same runners. So the Linux WASM backend is not universally broken:
whatever the bug is, it needs the shape of the concatenated ci-spec program
(229 cases, one module) to surface.

The `wasmtime` process being unresponsive suggests either an infinite loop
inside the WASM module or unbounded memory allocation eventually tripping the
runner's OOM enforcer. The runner-death mode -- silent, no stderr, no
Java-side timeout -- is what an OOM would look like from the harness'
perspective.

## Suspicious changes on develop since the last green

Commits touching `src/` between `6645c6d` (last green) and the first red run
that changed cross-backend WASM output:

    f0efc2c  Widen the WASM string byte model to UTF-8; unblock uax-15 on all
             four backends (todo 159)
    e954926  WASM (eq #\A #\A) = T on all four backends: TYPE_CHAR fallback
             after ref.eq (todo 162)
    d02a33d  interpreter LispString int[] per-slot; read-char returns one full
             code point (todos 160+161)

The primary suspect is `f0efc2c` -- it is the only one that rewrites the WASM
GC string byte payload wholesale (`_charvec_to_str` etc., see
`.kb/wasm-gc-strings.md`). If Linux `native-image` folds a byte-order-sensitive
Java constant differently from macOS in that path, the emitted `.wasm` could
carry a length or offset that indexes past its own buffer and drives the
compiled loop into an infinite bounce.

Not yet ruled out: `d02a33d` (per-slot int[] LispString) touched only the
interpreter path but sits next to the CHARACTER round-trip work whose WASM
side is `e954926`.

## What has been tried in the CI environment

Already landed as pinning tests / observations:

- `cd883bb` -- `CiSpecE2eTest.exec()` now drains stdout+stderr concurrently
  and enforces `EXEC_TIMEOUT_SECONDS = 300`. That rules out the classic pipe
  buffer deadlock; the hang is downstream of the harness.
- `bb182c8` -- per-sub-exec labels; that is how the hang was localised to
  `run-wasm` (compile-wasm returns cleanly in ~243 ms).

`sudo cp` -installed local binaries are unrelated to this (see
`.kb/macos-hardened-runtime-adhoc-provenance.md` if you land there): that
failure is Apple `AppleSystemPolicy` refusing to load a `linker-signed` adhoc
Mach-O whose provenance was lost by `cp`, not the runtime side.

## What to try next -- best done on a Linux amd64 host

1. **Reproduce off CI.** Build the develop-tip native binary on Linux amd64,
   concatenate the ci-spec corpus, and run the WASM leg by hand:

       ./mvnw -Pnative clean package -DskipTests
       # concatenate ci-spec.yaml sources into ci-program.lisp (the same shape
       # CiSpecE2eTest writes via YAMLMapper)
       ./target/rontolisp ci-program.lisp -o test.wasm
       ulimit -v 4194304  # cap the process so wasmtime cannot eat the host
       time wasmtime --wasm gc --wasm exceptions=y --dir . test.wasm

   If wasmtime hangs and the vsize cap kills it with a clear stderr, the bug
   is inside the `.wasm`. If it prints normally, the CI runner's resource
   posture is a co-factor and the fix is to add `timeout` / `ulimit` around
   the `wasmtime` exec in `CiSpecE2eTest.runBackend` for the Linux CI case.

2. **Diff the WASM outputs.** With Linux and macOS native binaries handy,
   compile the same `ci-program.lisp` on both, then run the modules through
   `wasm2wat` (or `wasmtime explore`). Look for divergence in the string
   runtime section (`_charvec_to_str`, `_str_char_at`, `_str_char_count`),
   any array-init loop, or a spot where the Linux module has a shorter data
   segment than the macOS one.

3. **Bisect between `6645c6d` and the first red native tip.** The candidate
   commits are `5acf6fe`, `5c33fe2`, **`f0efc2c`**, `d5ffdd4`, `d02a33d`,
   `e954926` -- do this by cherry-picking onto `6645c6d` and rebuilding the
   Linux native binary each time. Only step (1) needs to be reproduced.

4. **Instrument wasmtime.** If the reproduction is stable, run with
   `WASMTIME_LOG=trace` (or `-C debug-info=y`) and grab a backtrace once the
   hang has been running for a few seconds -- `gdb -p $(pgrep wasmtime)` then
   `bt`. A repeating call frame between the `_charvec_to_str` runtime and the
   host string builder would nail the string-model regression.

5. **Short-term CI defence** (independent of the actual fix): wrap the two
   wasmtime invocations in `CiSpecE2eTest.runBackend` (WASM and
   WASM_COMPONENT) with a `timeout 60 wasmtime ...` prefix on Linux. That
   turns the runner-death into a `exit=124` we can attribute cleanly and
   keeps the other backends in the same run from being cancelled as
   fail-fast peers. Do this *after* step 1 has confirmed whether the hang is
   memory-bound or CPU-bound so the tool choice (`timeout` vs `ulimit -v`)
   is honest.

## Non-goals

- Fixing the developer-experience UX of the failure. `CiSpecE2eTest` already
  logs the failing sub-exec; that is enough.
- Chasing macOS `AppleSystemPolicy` provenance rejection of the `sudo cp`
  -installed binary; that surfaced in the same session but is orthogonal
  (see `.kb/macos-hardened-runtime-adhoc-provenance.md` if it lands there).

## Related recent work in the same session (already pushed to `develop`)

- `daf5dc4` Link .todo/.history.md commit column to GitHub (docs only)
- `cd883bb` CiSpecE2eTest: drain stdout/stderr concurrently and bound each
  exec (rules out pipe deadlock)
- `bb182c8` CiSpecE2eTest: log each sub-exec with a label (localises the
  hang to run-wasm)
- `6699659` Run surefire test classes in two parallel forks (unrelated but
  in the same session; halves the test-job wall clock)
- `ba769f4` Run AsdfLibraryE2eSupport methods concurrently (unrelated; sets
  up for future Wasm-side parallelism)
