# Support `require` / `provide` (idempotent module loading)

Feasibility: high, assessed right after the defpackage work (commit follows
94604de). Unlike `defpackage` (resolver-only), the natural home is split in
two, both small and both following existing, proven patterns:

- **Compile path (JVM/WASM/native)**: `LoadInliner` (cli pkg), NOT
  `PackageResolver`. The inliner already splices a literal, top-level
  `(load "file.lisp")` with `SourceLoader.resolve` path resolution and a
  path-stack cycle guard. `require` = the same splice guarded by a `provided`
  set threaded through the inline recursion; `provide` = record + consume
  (replace with a quoted symbol, like `in-package`/`defpackage`). `load` is
  deliberately NOT idempotent (matches CL), so `require`'s value is exactly
  the diamond-dependency case (a.lisp and b.lisp both require utils).
  `LoadInliner` runs BEFORE `PackageResolver`, so `defpackage`/`in-package`
  inside a required file keep working in source order — composes cleanly with
  the new user packages.
- **Interpreter**: runtime functions, like `load`
  (`LispEvaluator.java:346` registers it as a runtime `LispFunction`); add a
  `*modules*`-equivalent `Set<String>` on the evaluator (alongside
  `loadDirStack`). REPL state persists per session automatically, like the
  resolver's `currentPackage`. The interpreter-runtime vs compiler-splice
  asymmetry is the same one `load` already has.

## Proposed v1 scope

- `(provide NAME)` and `(require NAME &optional "path.lisp")` as **literal,
  top-level directives** on the compile path; real runtime functions on the
  interpreter. NAME is a keyword/symbol/string designator (reuse
  `PackageResolver`-style designator parsing).
- Module-name-to-file mapping (implementation-defined in CL): explicit second
  argument wins; without it, resolve `NAME.lisp` relative to the requiring
  file (top-level entry falls back to CWD), i.e. exactly `SourceLoader`'s
  existing rules for `load`.
- `require` of an already-provided module is consumed / returns the name
  without loading. A required file is expected to `(provide NAME)` itself;
  requiring splices the file either way and the provide inside it marks the
  set (also guards self-require via the existing cycle stack).
- `*modules*` variable: NOT supported in v1 (a compile-time rewrite would be
  a per-position snapshot — half-baked). Document in missing-features.
- require/provide inside a file read by the **runtime** `load` of compiled
  output: unsupported, same documented limitation as `in-package` there.

## Work items

1. `LoadInliner`: `provided` set + `require`/`provide` handling (consume to
   quoted symbol; splice-once). Tests: `LoadInlinerTest` (diamond deps,
   provide-first, explicit path, missing file, nested/non-literal left
   untouched or rejected — see open decision 2).
2. `LispEvaluator`/`Environment`: runtime `require`/`provide` functions +
   module set; `require` delegates to the existing `load` machinery
   (loadDirStack for relative resolution). `LispEvaluatorTest` cases.
3. Classification (open decision 1) + `LispNames` constants +
   `PackageRegistry` set membership. If classified as special forms, the
   pinned `list-special-forms` list changes AGAIN in 8 places: ci-spec
   (~line 976), LispEvaluatorTest / JvmLispCompilerTest /
   WasmLispCompilerIntegrationTest, doc/{en,ja}/reference/packages.md,
   doc/{en,ja}/reference/functions/rontolisp-list-special-forms.md.
4. Backend tests: JVM/WASM `compileAndRun` with temp files (mirror how load
   is tested there). Note ci-spec CANNOT easily cover this: it concatenates
   cases into one single program (no load cases exist there today either),
   and WASM would need a `--dir` preopen. E2E coverage lives in the unit /
   integration tests instead; document that in the case comment if needed.
5. Docs: reference pages for require/provide (en+ja) + `_catalog.yaml`
   entries (under functions/ or special-forms/ per decision 1),
   missing-features.md (en+ja; currently does not mention require at all),
   the load page + `.kb/load-inliner.md` and `.kb/read-load-streams.md`
   updates, doc-fix helper + DocExamplesTest (remember: one shared evaluator
   per PAGE — don't provide the same module twice across blocks; see the
   defpackage.md `:util`/`:mypkg` split).
6. Format, full `./mvnw test`, `-Pweb compile`, `javadoc:jar`, native image +
   `CiSpecE2eTest` (list-special-forms pin changes if decision 1 = special
   form).

## Open decisions

1. **Special forms vs functions.** In CL, require/provide are functions.
   Option A: treat like `in-package`/`defpackage` (special forms, no function
   value, `#'require` is an error) — consistent with "consumed directive"
   semantics but changes the pinned special-forms listing. Option B: the
   `rontolisp:wasm-export` pattern — real functions on the interpreter,
   compile-time-directive-only on the compilers (non-literal/nested = compile
   error). B is closer to CL and keeps the pinned lists stable; leaning B.
2. Nested / non-literal `require` on the compile path: hard error (like
   nested `defpackage`) or leave untouched for the runtime reader (like
   nested `load`, which the compiled runtime `load` CAN execute — but the
   runtime reader does not know require, so it would fail anyway). Leaning
   hard error for require/provide specifically.
3. Should `provide` of an already-provided name be an error or a no-op?
   CL: no-op (pushes duplicate onto `*modules*` at worst). Leaning no-op.
