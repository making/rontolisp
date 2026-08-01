# Shim widening for clack: uiop symbol-call + uiop/image, usocket host resolution, swank stub

Difficulty: 低 (three independent small edits to existing shim machinery;
each has a direct precedent)

Part of the Clack milestone `.todo/223`.

## 1. uiop: exported, real `symbol-call`; `uiop/image` package

- `uiop:symbol-call` — lack/util's `load-with-quicklisp` references it at READ
  time (single colon), so the whole `util.lisp` fails to load. The symbol
  exists but is INTERNAL today ("not external in the UIOP package" — spike).
  Make it a real exported member: `(apply (intern (string name) (find-package
  (string pkg))) args)` with a package-designator-tolerant lookup. postmodern's
  json-encoder probe wants it too, same spelling.
- `uiop/image` package with exported `print-condition-backtrace` —
  `lack-middleware-backtrace` does `(:import-from :uiop/image
  :print-condition-backtrace)`, which dies when the package is absent. No
  backend has a Lisp-level backtrace, so the honest lite version prints the
  CONDITION to `:stream` (`(format stream "~A~%" condition)` shape — the spike
  used exactly this and the middleware worked). Real uiop houses it in the
  `uiop/image` package that `uiop` also re-exports; register the package
  properly in `PackageRegistry` seeding so both spellings resolve.

## 2. usocket: `host-to-hostname`, `get-host-by-name`

`clack.handler:run` calls both when `:address` is given (it always normalizes
the address before passing it to the backend handler). Missing from the shim
(spike hit "HOST-TO-HOSTNAME is not external"). Interpreter/JVM: resolve via
`InetAddress` (`get-host-by-name` -> address, `host-to-hostname` -> dotted
string). WASM: follow the existing shim policy — address accessors return
nil/identity (the shim source records the splice constraint; `.kb/tcp-sockets.md`).

## 3. swank: stub built-in system

`clack.asd` hard-depends on `"swank"`, whose real `.asd` (slime) is a program
we can never parse — without intervention `ql:quickload` downloads the slime
tarball and dies. Add a `BuiltinSystems` stub system "swank" whose one shim
source defines package `swank` exporting `create-server` / `stop-server`:
`create-server` signals "swank is not supported on rontolisp" (clack only
reaches it when `:swank-port` is passed), `stop-server` is a nil no-op.
Remember the native-image `resource-config.json` entry (`.kb/asdf.md` gotcha).

## Test

- uiop/usocket: units beside the existing shim tests; the E2E lives in
  `.todo/228`'s ClackE2eTest (loads the UNPATCHED cached sources).
- swank: `AsdfSystemsTest` — `:depends-on ("swank")` resolves without network;
  calling `swank:create-server` signals.

## Status: DONE 2026-08-01 (all four backends)

Verified by hand on the interpreter, the JVM, WASM Preview 1 and
`--component` (byte-identical output on all four except `uiop:symbol-call`,
see below), plus the native binary for the `resource-config.json` entry.

What landed, and the two decisions that deviate from the plan above:

1. **`uiop:symbol-call`** is external (`PackageRegistry`) and REAL on the
   interpreter only — a `LispEvaluator` global over
   `packageResolver.memberSpelling` + `resolveFunction` + `apply`, with
   `find-symbol*` error semantics. The compile backends let the existing
   generic `expandUiopStubCall` lowering take it (evaluate args, signal at CALL
   time), because a runtime name-to-function table is `.todo/229` and every
   library that spells it reaches it on a cold branch. A static fold of the
   literal `(uiop:symbol-call :ql :quickload ...)` shape into a direct call was
   considered and REJECTED: lack's sibling call names `ql:system-not-found-name`,
   which does not exist, so folding would turn a compilable cold branch into a
   hard compile error. Re-evaluation trigger: when `.todo/229` lands, this
   should become a real runtime lookup on the compile paths too.
   The hard requirement it satisfies was READ time — lack's `src/util.lisp`
   spells it with a single colon, so an internal-only symbol failed the whole
   FILE.
2. **`uiop/image:print-condition-backtrace`** is a `LispPreludeLibrary` entry
   (all four backends), defined in `uiop/image` with the `uiop` package
   IMPORTING the name, so both spellings are ONE symbol and hence one splice.
3. **usocket `host-to-hostname` / `get-host-by-name`** are pure Lisp in
   `usocket.lisp`. `host-to-hostname` is REAL for every designator shape.
   `get-host-by-name` renders through it instead of resolving — **the plan's
   "interpreter/JVM: resolve via `InetAddress`" was deliberately not taken**:
   there is no cross-backend resolver primitive to add it behind, resolving on
   two backends only would be a real divergence in a library spliced into every
   socket program, and the value buys nothing (the composite feeds an address
   straight back into `tcp-connect`/`tcp-listen`, which resolves it in the host
   anyway). Recorded with its re-evaluation trigger (`.todo/048`) in
   `.kb/tcp-sockets.md`. A bonus: both answer on Preview 1, unlike the rest of
   the shim.
4. **`swank`** is a built-in shim system (`swank.lisp` + `ShimLibraries` +
   `BuiltinSystems` + the `resource-config.json` entry, verified on the native
   binary).

Tests: `LispEvaluatorTest` (symbol-call incl. both error shapes,
print-condition-backtrace under both spellings, the two usocket entries),
`LispPreludeLibraryTest.bothUiopSpellingsOfPrintConditionBacktraceSelectTheOneEntry`,
`LispEvaluatorAsdfTest.dependsOnBuiltinSwankResolvesWithoutNetworkAndCreateServerSignals`,
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` (usocket host
rendering + print-condition-backtrace on the compile paths). No ci-spec case:
the shim surfaces follow the bordeaux-threads/babel precedent (unit tests +
manual four-backend verification), and the swank leg needs an `asdf` load,
which the concatenated ci-spec driver cannot provide.

Docs: `guides/asdf-systems.md` shim table (swank row + the widened uiop row),
`reference/functions.md` uiop + usocket tables, and three new per-operator
pages (`uiop-symbol-call`, `uiop-print-condition-backtrace`,
`usocket-host-names`) — en + ja, with `_catalog.yaml` entries.
`.kb/asdf.md` + `.kb/tcp-sockets.md` updated.
