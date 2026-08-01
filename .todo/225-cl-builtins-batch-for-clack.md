# Missing CL builtins the clack/lack sources call

Difficulty: 低〜中 (each item is small; the batch is medium because every
function follows the full 4-backend implementation order + wrappers + docs.
WASM legs that cannot be honest become call-time stubs, the todo-195 policy)

Part of the Clack milestone `.todo/223`. All verified missing by an interpreter
probe on 2026-08-01. Call sites in parentheses.

1. **`substitute-if`** (+ `substitute-if-not` for parity) —
   `lack/util:find-middleware` builds the middleware variable name with it at
   RUNTIME, so `builder` with a keyword middleware (`:backtrace`, the clackup
   default) dies without it. `substitute` exists; follow its lowering.
2. **`file-write-date`** — `clack:eval-file`'s app-file cache. Interpreter/JVM:
   `Files.getLastModifiedTime` -> universal time; WASM: call-time stub (P1) /
   honest answer if the WASI clock+fs API allows (component).
3. **`sleep`** — `clack.handler:stop` does `(sleep 0.5)` after destroying the
   acceptor thread. Interpreter/JVM: `Thread.sleep`; WASM: call-time stub or
   busy-wait — decide and record why.
4. **`ensure-directories-exist`** — the backtrace middleware's pathname
   `:output` branch. Interpreter/JVM real; WASM call-time stub.
5. **`file-length`** — `lack/util:content-length` for a pathname body
   (`(with-open-file (in body) (file-length in))`). Today it resolves but
   returns NIL on a fresh input stream (probed) — make it answer the actual
   length for file streams on interpreter/JVM.
6. **runtime `export`** (+ `unexport`) — not called by clack itself but needed
   the moment any loaded library manages exports at runtime (the spike wanted
   it for a workaround; alexandria-adjacent libraries call it). Interpreter
   package op; compile paths per `.kb/packages.md` policy.
7. **Check, may already work**: let-binding `*readtable*` / `*load-pathname*` /
   `*load-truename*` (clack `%load-file` binds all three; only the FILE-app
   path of clackup reaches it). If the specials do not exist, define them as
   let-bindable specials with honest values in `load`.

Not in this batch (own todos): `subtypep` on class metaobjects (`.todo/230`),
thread creation (`.todo/227`).

## Test

ci-spec case `clack-enablement-builtins` for the cross-backend members +
`LispEvaluatorTest` units. `file-length`/`file-write-date` need a file fixture,
so they get `LispEvaluatorTest`/JVM-compiler tests instead of ci-spec.

## Status: DONE 2026-08-01 (all seven items, four backends)

All four backends verified (interpreter / JVM class / WASM Preview 1 / WASM
`--component`), `./mvnw test` green (5099), the native `CiSpecE2eTest` green on
all four, `-Pweb compile` and `javadoc:jar` (0 warnings) green.

Per item:

1. **`substitute-if` / `substitute-if-not` / `nsubstitute-if` /
   `nsubstitute-if-not`** -- the whole CL family, not just the two named: one
   `LispMacroExpander.expandSubstituteIf` shape (`expandSubstitute`'s do-scan with
   the `eql` test replaced by a predicate call), so all four backends share it.
   `:key` only -- the predicate IS the test -- validated by `requireKeywords`.
2. **`file-write-date`** -- interpreter through a new `SourceLoader.writeDate`
   (so the browser playground answers rather than fails), JVM `_fileWriteDate`.
   Both WASM backends answer `nil`, which is CL's own "cannot be determined"
   answer, NOT a stub.
3. **`sleep`** -- shared seconds->milliseconds expansion; interpreter/JVM park
   (`%sleep-ms` / `Thread.sleep`). `--component` waits on the REAL wasi:clocks timer
   (the spliced `wait.lisp` defun forcing the future through
   `rontolisp::%future-force`) -- measured 0 CPU for a 2 s sleep, where a spin cost
   2.16 s of it -- at the price of async/EH mode, i.e. `-W exceptions=y`, which the
   user accepted explicitly. Only Preview 1 still spins on the clock: it has no
   timer to wait on. Reason + the three constraints that leave exactly one shape +
   re-evaluation trigger in `.kb/time-environment-builtins.md`.
4. **`ensure-directories-exist`** -- a `LispPreludeLibrary` defun over one new
   `%make-directories` primitive (the write-side sibling of `%list-directory`),
   so the "which part of the namestring is the directory" rule has one
   definition. WASM signals: its contract has no nil escape. Lite: no second
   (`created`) value, per `.todo/212`.
5. **`file-length`** -- real on interpreter + JVM via a handle->namestring side
   table (`streamPaths` / `_streamPaths`, cleared on `close`), flushing through
   `_forceOutput` first. WASM `nil`, in contract like item 2.
6. **`export` / `unexport`** -- consumed by `PackageResolver` exactly like
   `use-package`, so they work on every backend; interpreter also binds them as
   runtime functions for computed calls. **Documented limit found in the
   process: export must PRECEDE the definitions**, because a symbol is
   identified by its canonical spelling here and exporting flips `pkg::name` to
   `pkg:name`. Recorded in `.kb/packages.md` with `.todo/156` as the
   re-evaluation trigger.
7. **`*readtable*` / `*load-pathname*` / `*load-truename*`** -- already
   let-bindable on the interpreter; `*readtable*` did NOT compile
   (`Cannot compile symbol reference: *READTABLE*`) and now rides
   `injectMvSpillGlobal`'s load-context declaration list.

Cross-cutting finding worth keeping: `#'file-length` / `#'file-write-date` had to
join `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`. Their wrapper bodies
call JVM runtime helpers that are gated on the SOURCE program naming the
operator, and the gate does not scan injected wrappers -- the self-call check in
`JvmLispCompiler` caught it loudly, which is the same trap `APPLY_USING_FUNCTIONS`
documents.

`rontolisp:list-functions` count moved 353 -> 362 (nine new CL functions); the
four pins (ci-spec, `LispEvaluatorTest`, `JvmLispCompilerTest` x2,
`WasmLispCompilerIntegrationTest`) moved together.
