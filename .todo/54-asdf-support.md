# 54: ASDF support (limited) -- investigation and phased plan

Goal: let rontolisp consume a slice of the existing CL ecosystem through
ASDF-style system definitions (`.asd` files, `asdf:load-system`), even if the
supported subset is small at first. This file records the 2026-07-05
investigation: what real ASDF needs, what rontolisp already has, the verified
blockers, and a phased plan.

> **Status 2026-07-05: Phase 1 is DONE** (mini-ASDF shim: `asdf` package,
> `asdf:defsystem` + `asdf:load-system` on all backends, `--system-path` +
> `RONTOLISP_SOURCE_REGISTRY` search path, docs + `.kb/asdf.md`). Implementation
> notes and deviations from the plan below:
>
> - The compile path is NOT a separate `AsdfInliner` pass: the handling lives
>   inside `LoadInliner`'s recursion (a loaded file can call `load-system`, a
>   spliced component can `load`/`require`), with the shared parsing/ordering
>   logic in `eval/AsdfSystems.java`.
> - `.asd` files are parsed as plain data on BOTH paths (the interpreter does
>   not evaluate them either), so no `asdf-user` package was needed --
>   `(in-package :asdf-user)` inside a `.asd` is skipped as data.
> - `(require :name)` was NOT wired to systems (Phase 1 v1 scope cut; a
>   `require` fallback to `NAME.asd` remains a possible follow-up).
> - No ci-spec case: the compile path needs the `.asd` on disk at compile time,
>   which the concatenated ci-spec driver cannot provide. Coverage:
>   `AsdfSystemsTest`, `LispEvaluatorAsdfTest`, asdf cases in `LoadInlinerTest`
>   (incl. compile-and-run-on-JVM), manual 4-backend verification, and the
>   native `CiSpecE2eTest` run (572 green, no cross-backend output shifted).

> **Status 2026-07-05 (later the same day): Phase 2 is DONE** (reader + package
> gaps; details in `.kb/reader-features.md` + `.kb/packages.md`). Notes and
> deviations from the plan below:
>
> - `*features*` + `#+`/`#-` (with `:and`/`:or`/`:not`, keyword or bare, case-
>   insensitive) live in the frontend lexer/reader (`reader.Features`,
>   `LispLexer`): the raw "skip one datum" mode exists as planned
>   (`LispLexer.skipDatum`, handles strings/char literals/comments/nested
>   `#+`/`#.` like `*read-suppress*`). Features: `:rontolisp` + one of
>   `:rontolisp-interpreter`/`-jvm`/`-wasm`; the CLI picks by output target and
>   `LoadInliner`/the playground thread it through, so a compiled program's
>   feature set is fixed at compile time. `*features*` is substituted at read
>   time like `pi` (bare spelling only; no `setq`). The compiled runtime
>   readers (`read`/`read-from-string`/runtime `load`) do NOT know the new
>   syntax — documented in read-load-limitations.
> - `#| ... |#` nests per CL. `#:foo` is NOT gensym-renamed (a designator needs
>   its original name); it reads as a plain symbol, passes through the resolver
>   unresolved, and every designator (defpackage clauses, asdf) strips `#:`.
> - Nicknames: `PackageRegistry` nickname map (`common-lisp`/`common-lisp-user`
>   seeded) + `defpackage :nicknames`; resolution canonicalizes everywhere.
> - `defpackage`: `:import-from` = textual imports map on `LispPackage`
>   (checked before the cl branch, so use-nothing packages work; qualified
>   references redirect to the source package), `:documentation`/`:size`
>   ignored, `:shadow`/`:shadowing-import-from` tailored errors.
> - `:if-feature` on components (any type, module included): the component
>   keeps its dependency-graph slot but contributes no files when disabled.
> - `#.` is now a clear read error (it used to silently mis-lex); the tolerant
>   skip-with-warning mode (`readAllSkippingReadEval`) applies to any `#.` in a
>   `.asd`, not just a leading top-level one (simpler; a mid-defsystem `#.`
>   still fails clause parsing loudly).
> - ci-spec: `reader-block-comments` / `reader-feature-conditionals` /
>   `reader-per-backend-features` (first `expectedByBackend` use) /
>   `reader-features-variable`; native `CiSpecE2eTest` 588 green on all four
>   backends.
>
> Next up: Phase 3 (language gaps for the "simple library" tier) below.

## Verdict up front

Porting real ASDF (asdf.lisp + UIOP, ~15k lines of CL) is not feasible in the
foreseeable future: it is built on CLOS (`defsystem` classes, `operate` generic
functions), the condition/restart system, dynamic (special) variables, the full
pathname/filesystem API, `eval-when`, runtime package manipulation
(`intern`/`find-package`/`export`), readtables, and multiple values -- every one
of which rontolisp lacks. The realistic path is an **API-compatible mini-ASDF
shim** ("rontolisp-asdf"): parse `.asd` files as *data* (the reader can already
do this, verified below), resolve component/dependency order ourselves, and
drive the existing `load` machinery. Users' `defsystem` files then work
unchanged as long as they stay inside the supported subset, and the shim can
grow toward real ASDF as language gaps close.

## What already works today (verified 2026-07-05 on the native binary)

- The reader reads a typical `(defsystem "x" :depends-on (...) :components
  ((:file "package") (:file "main" :depends-on ("package"))))` form as plain
  data with no changes.
- A multi-file package -- `package.lisp` with `defpackage`/`:export` +
  `main.lisp` with `in-package`/`defun`, loaded via two `(load ...)` calls --
  works on **both** the interpreter and the compiled JVM path (LoadInliner
  splices the files, PackageResolver then sees the defpackage). This is exactly
  the shape a mini-ASDF would emit, so the substrate exists.
- Computed `load` paths (`(load (concatenate 'string dir "package.lisp"))`)
  work at runtime in the interpreter; `SourceLoader` is a pluggable
  `@FunctionalInterface`, the natural injection point for a system registry.
- `require`/`provide` already give idempotent module loading on both paths
  (LoadInliner at compile time, real functions in the interpreter).

## Verified hard blockers (each dies immediately today)

| Feature | Failure today | Needed by |
| --- | --- | --- |
| `#+` / `#-` + `*features*` | `The variable #+sbcl is unbound` | nearly every real library and `.asd` |
| `#\| ... \|#` block comments | `The variable #\| is unbound` | common in library headers |
| `#.` read-time eval | not lexed | `.asd` version guards (e.g. split-sequence) |
| `#:foo` uninterned symbols | mis-lexed | `defpackage` in almost every library |
| `common-lisp` package nickname | `No such package: #:common-lisp` | `(:use #:common-lisp)` |
| `defpackage` `:import-from`/`:nicknames`/`:shadow`/`:documentation` | `Unsupported defpackage clause` | most libraries |
| `eval-when` | `The function eval-when is undefined` | macro-exporting libraries |

## Case study: split-sequence v2.0.1 (a canonical "simple" library)

Even this small classic needs, beyond the table above: `values` (57 uses) +
`multiple-value-bind` (.todo/32), `loop` clauses (.todo/29), `declaim`/`declare`
(no-op is fine, .todo/35), `check-type` (8 uses, .todo/39), `flet` (.todo/34),
`etypecase` (exists), `defmacro` with full lambda lists (.todo/44), `error`
(exists). Its `.asd` additionally uses `#.`, `:read-file-form`, `:if-feature`,
`:in-order-to`. `defgeneric`/`defmethod` appear only in
`extended-sequence.lisp`, which is gated `:if-feature (:or :sbcl :abcl)` -- so
`:if-feature` support lets us skip the CLOS file entirely. Conclusion: "simple"
ecosystem libraries need Phases 1-3 below; none are loadable from Phase 1 alone.

## Phased plan

### Phase 1: mini-ASDF shim (useful immediately for users' own code)

- New `asdf` package (plus `asdf/defsystem` nickname handling if cheap) exposing
  `asdf:defsystem` and `asdf:load-system` (and wiring `(require :name)` to it).
- Implementation shape mirrors `LoadInliner` (compile path) + the interpreter's
  runtime `load`:
  - **Compile path**: an `AsdfInliner` cli pre-pass. A literal top-level
    `(asdf:load-system "name")` locates `name.asd` via a source registry
    (CLI flag `--system-path DIR` repeatable + `RONTOLISP_SOURCE_REGISTRY` env
    var + the loading file's directory), reads the `.asd` as data, resolves the
    supported `defsystem` subset, topo-sorts `:components` by `:depends-on`
    (or `:serial t`), and splices the component files in order -- reusing the
    LoadInliner splice/cycle-guard/provide bookkeeping so JVM and WASM see the
    definitions natively.
  - **Interpreter**: same resolution logic at runtime (computed system names
    allowed), layered on the runtime `load` + `providedModules`.
- Supported `defsystem` subset (v1): `:description`/`:version` (literal string)
  /`:author`/`:license`/`:maintainer` (recorded or ignored), `:depends-on`
  (system names resolved through the same registry), `:serial`, `:components`
  with `(:file "name" [:depends-on (...)])`, `:static-file` (ignored),
  `(:module "dir" :components (...))` (path prefix). Anything else --
  `:defsystem-depends-on`, `:in-order-to`, `:perform`, `:if-feature` (until
  Phase 2), `(:read-file-form ...)`, `#.` -- is a clear compile error naming
  the unsupported clause.
- System deduplication: loading the same system twice is a no-op (align with
  the existing `require`/`provide` semantics).
- Deliverable: users can structure their own rontolisp projects as
  `.asd`-defined multi-file systems on all backends today, and third-party
  systems whose sources happen to fit rontolisp load unchanged.

### Phase 2: reader + package gaps (the read-layer blockers)

- `*features*` + `#+`/`#-` with feature expressions (`:and`/`:or`/`:not`).
  Features: `:rontolisp` always, plus per-backend (`:rontolisp-interpreter`
  /`:rontolisp-jvm`/`:rontolisp-wasm`), `:common-lisp`? (no -- do not lie).
  Note the skip path must lex (not parse) the guarded form so that a form using
  unsupported syntax can still be skipped -- this needs a raw "skip one datum"
  mode in the lexer.
- `#| ... |#` (nesting per CL), `#:foo` uninterned symbols (inside `defpackage`
  treat like the keyword designator; elsewhere a fresh gensym-like symbol).
- Package nicknames: `common-lisp` -> `cl`, `common-lisp-user` -> `cl-user`.
- `defpackage`: `:import-from` (works naturally since resolution is textual --
  map imported names to the source package), `:nicknames`, `:documentation`
  /`:size` (ignore), `:shadow`/`:shadowing-import-from` (explicit error for
  now).
- `:if-feature` in the shim (evaluate against `*features*`; enables skipping
  CLOS-only files like split-sequence's extended-sequence.lisp).
- `#.`: keep as an error, but have the shim tolerate a leading top-level `#.`
  form in `.asd` files by skipping it with a warning (version-guard idiom).

### Phase 3: language gaps for the "simple library" tier

All have existing .todo items; ASDF support is the forcing function to
prioritize them: multiple values (.todo/32), `labels`/`flet` (.todo/34),
`destructuring-bind`, `eval-when` (accept and treat as `progn` for
`:load-toplevel`/`:execute`; the compile path's expand-before-codegen pipeline
already approximates `:compile-toplevel` for macros), `declare`/`declaim`
/`proclaim` as parsed no-ops (.todo/35), `check-type`/`assert` (lite versions
that `error`), full `defmacro` lambda lists (.todo/44), `loop` gaps (.todo/29),
`with-output-to-string`/string streams (.todo/36), runtime symbol API
`intern`/`symbol-name`/`find-symbol` (.todo/38).

Target library to keep honest: **split-sequence** end-to-end (minus
extended-sequence via `:if-feature`), added to ci-spec once green.

**Split into session-sized units (2026-07-05).** Phase 3 is too large for one
session; each unit below is one self-contained session (implement + tests +
docs + native E2E), in the recommended order. The wishlist todos (29/32/34/35
/36/38/44) stay open for the leftovers each unit does not take.

1. Declarations/eval-when/check-type/assert -- `declare`/`declaim`
   /`proclaim` no-ops, `the`, `eval-when` (+ top-level flattening),
   `check-type`/`assert` lite. **DONE 2026-07-05**; the todo file (55) was
   removed, details live in `.kb/declarations-type-checks.md`.
2. `.todo/56-flet-labels.md` -- `flet`/`labels` via expansion to let-bound
   lambdas (macrolet stays in 34).
3. `.todo/57-multiple-values.md` -- `values`/`multiple-value-bind`/`-list`
   /`-call`/`nth-value` + floor/gethash secondary values (the rest stays
   in 32). Deepest unit; plan-mode session.
4. `.todo/58-destructuring-bind-and-defmacro-lambda-lists.md` --
   `destructuring-bind` + full defmacro lambda lists (shared destructuring
   walker; lifts LoopExpander.destructure).
5. `.todo/59-string-streams.md` -- `with-output-to-string`
   /`with-input-from-string`/`write-string`/`write-to-string` over the
   existing stream-handle runtime (the rest of 36 stays there).
6. `.todo/60-symbol-runtime-api.md` -- `intern`/`find-symbol`/`symbol-name`
   /`make-symbol`/`boundp`/`fboundp`/`symbol-value` (package mutation stays
   in 38).
7. `.todo/61-split-sequence-e2e.md` -- the integration target: vendor
   split-sequence, load via `asdf:load-system` on all four backends, fix the
   residue, close Phase 3.

### Phase 4: bigger substrate (medium libraries; still not real ASDF)

Condition system + `unwind-protect` (.todo/39), dynamic/special variable
binding (today `defvar` is a plain global define, scoping is lexical-only --
this is a deep evaluator/compiler change), CLOS subset (.todo/40), a pathname
layer (`merge-pathnames`, `probe-file`, `*load-pathname*`), readtables
(.todo/41). Only after these does aiming the shim at UIOP-lite territory make
sense. Quicklisp-style fetching (`ql:quickload` over `rontolisp:fetch`) is
possible in principle but out of scope until Phases 1-3 exist.

## Open questions

- Should `asdf:load-system` on the compile path require literal system names
  only (LoadInliner precedent says yes)?
- Registry default: just `./` + `--system-path`, or also a conventional
  `~/.rontolisp/systems/`?
- Does the shim live as a Java pre-pass only, or partially as a Lisp-source
  library (`asdf.lisp`, linalg pattern)? The registry/file-IO half must be Java;
  the defsystem-parsing half could be either. Leaning all-Java for v1 since the
  compile path needs it before any Lisp runs.
