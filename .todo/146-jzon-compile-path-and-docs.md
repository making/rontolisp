# jzon: compile-path (JVM/WASM) support + remaining docs

The real com.inuoe.jzon v1.1.4 loads and runs on the INTERPRETER
(`(ql:quickload '#:com.inuoe.jzon)` end-to-end against the live Quicklisp
dist; `JzonE2eTest` pins the vendored copy under `src/test/resources/jzon`).
The language features it forced are interpreter-complete but NOT yet wired
into the JVM/WASM compile path. This todo tracks finishing the feature per
the repo's implementation order (interpreter -> JVM -> WASM -> ci-spec ->
docs).

**Priority (user, 2026-07-18): WASM support is wanted even with feature
limitations.** Full jzon on wasm-GC is blocked on the 64-bit/bignum numeric
model (eisel-lemire/schubfach), but the FOUNDATIONAL features (tagbody/go,
shiftf, typep, fill-pointer strings, multi-arg dispatch, `#.`, ...) do not
need it -- wire those to BOTH compiled backends, and for jzon itself
consider a wasm-GC mode where float parse/print take a degraded path (or a
clear error) while integer/string/structure JSON works.

## Compile-path gaps (in rough dependency order)

- `#.` read-time eval: the marker read
  (`LispReader.readAllWithReadEvalMarkers`) + substitution walk live in
  `LispEvaluator.loadFile` only. `LoadInliner`/`UserMacroExpander` need the
  same read + resolution against the macro-time evaluator, per top-level
  form. The backquote-split marker also relies on the `%read-eval` identity
  function (Environment) -- the compilers need an equivalent (a 1-arg
  identity emit).
- `tagbody`/`go`/`prog`/`prog*`: interpreter-only special forms
  (`GoSignal` + `evalTagbody`). JVM = real jumps (OperandStack model
  interplay for `go` in argument position); WASM = block/br or a state
  machine.
- Multi-parameter method dispatch + variadic generic lambda lists +
  defgeneric `(:method ...)` clauses: `LispMacroExpander` work is
  backend-free (expansions), so the compilers mostly inherit it, BUT
  `expandTopLevelDefinitions` inline-method splicing and the `apply`-based
  variadic dispatcher forwarding need corpus coverage on JVM/WASM.
- `typep`/`subtypep`: `typep` expands statically (backend-free);
  `subtypep` is an evaluator-registered function -- compilers reject it.
  Needs a compile-time constant fold (both args quoted) + runtime fallback
  decision.
- New Environment built-ins absent from the compilers: `mask-field`,
  `scale-float`, `%ieee754-*` (JVM: Double.doubleToRawLongBits etc.; WASM:
  i64.reinterpret_f64 -- but bits > i31 need the WASM bignum story, see
  below), `char-name`, `fdefinition`, `file-position`/`file-length`,
  `make-broadcast-stream`, `pathnamep`, `input-stream-p`/`output-stream-p`,
  `stream-element-type`, `class-of`, `slot-boundp`/`slot-makunbound`,
  `simple-condition-format-control`/`-arguments`, `write-string`
  `:start`/`:end`, `replace` nil bounds.
- Fill-pointered/adjustable STRINGS (`LispString` fill pointer):
  interpreter-only. The JVM/WASM string representations need the same
  make-array shapes, vector-push-extend/adjust-array/fill-pointer support.
- `with-slots` write-through substitution + let-fallback: expansion is
  backend-free; verify on JVM (the let fallback relies on nothing
  interpreter-specific).
- `(setf (values ...))`, `shiftf`, `load-time-value`, setf-through-`the`:
  backend-free expansions; wire the evalCons-equivalent cases into
  `Jvm/WasmExprCompiler.compileCons` and add unit tests.
- Gray streams: `GrayStreamsLibrary` (rontolisp's own protocol) needs a
  compile-path pre-pass (the usocket `process()` pattern) + the
  write-string instance dispatch in the compiled runtimes.
- defstruct options / defclass `:default-initargs` / eql-quoted-atom
  specializers / `standard-object` type: expansions are backend-free;
  needs JVM/WASM test coverage.
- `*features*` as an interpreter global: the compilers still substitute at
  read time (`Features.substituteFeaturesVar()`); a program compiled from a
  file whose lambda list binds `*features*` will still break there --
  acceptable (document) or fix like the interpreter.
- `(symbolp nil)`/`(symbolp t)` now t on the interpreter (CL semantics,
  matches the doc prose); the COMPILED backends still answer nil --
  cross-backend divergence to align (JVM/WASM symbolp emitters).
- `case`/`ecase` package-qualified key vs quoted data: `makeCaseEq` now
  emits a both-spellings test (backend-free expansion, so compiled output
  changed shape); run the native ci-spec E2E to confirm no output shift.
- WASM: jzon is likely OUT OF REACH on wasm-GC until the numeric model
  grows 64-bit/bignum integers (eisel-lemire/schubfach do u64/u128
  arithmetic; `(ash 1 128)` wraps today). Document as a limitation.

## MANDATORY when wiring the compile path

- **Update the "interpreter only for now" documentation** as each feature
  lands on JVM/WASM: the 24 per-operator pages (en + ja, both the detail
  page note and the curated table row in
  functions.md/macros.md/special-forms.md) all carry the note "Supported on
  the **interpreter only** for now; the JVM and WASM compilers do not
  support it yet" / "現時点では**インタープリタのみ**でサポートされます" --
  replace it with the real backend coverage sentence (or delete it when all
  backends support the operator), page by page, in the SAME commit as the
  backend work. Grep anchor: `interpreter only for now` (en) /
  `現時点ではインタープリタのみ` (ja).

## Test/infra follow-ups

- Migrate `JzonE2eTest` onto `AsdfLibraryE2eSupport` (all-backend) once the
  JVM path works; add a `jzon-residue-features` ci-spec case for the
  general features (tagbody/go, shiftf, typep, setf-values, fill-pointer
  strings, multi-arg dispatch).
- Native ci-spec E2E (`-Pnative` + `CiSpecE2eTest`) after any of the above.
- `ExamplesE2eTest`: consider an `examples/asdf` jzon demo + README row
  (ASDF library integration checklist).

## Documentation (en + ja, same file set, byte-identical fences)

DONE 2026-07-18: per-operator pages + `_catalog.yaml` entries + curated
table rows (functions.md/macros.md/special-forms.md) for all 24 new
operators (`tagbody`, `go`, `prog`, `prog*`, `shiftf`, `load-time-value`,
`typep`, `subtypep`, `mask-field`, `scale-float`, `char-name`,
`fdefinition`, `file-position`, `file-length`, `make-broadcast-stream`,
`pathnamep`, `input-stream-p`, `output-stream-p`, `stream-element-type`,
`class-of`, `slot-boundp`, `slot-makunbound`,
`simple-condition-format-control`/`-arguments`), each marked "interpreter
only for now", plus the `with-slots` write-through rewrite (row + page).
DocExamplesTest green, docgen builds. **When the compile path lands, sweep
these pages and REMOVE the interpreter-only notes.**

Still to write:

- New pages also for the package-nickname surface: the `defpackage`
  `:local-nicknames` clause and `uiop:add-package-local-nickname` (both
  lite: the nickname is GLOBAL, no per-package scoping -- the jzon README's
  `(uiop:add-package-local-nickname '#:jzon '#:com.inuoe.jzon)` idiom).
  Compile path: the clause is consumed by `PackageResolver` (shared), but
  the uiop FUNCTION is interpreter-only -- decide whether the LoadInliner
  should consume a top-level literal call like it consumes defpackage.
- Update existing pages: `defstruct` (options), `defgeneric`/`defmethod`
  (multi-parameter specializers, `:method` clauses, `&optional`/`&rest`),
  `defclass` (`:default-initargs`), `with-slots` (write-through),
  `make-array` (`:fill-pointer`/`:adjustable` character arrays = strings,
  `:initial-contents` sequences), `write-string` (`:start`/`:end`),
  `symbolp` (nil/t -- prose already says t; now true), `*features*`
  (interpreter variable vs compile-time substitution), `#.`/reader labels
  in the reader guide, Gray streams guide (rontolisp protocol +
  trivial-gray-streams shim), `asdf-systems` guide rows for the new
  built-in systems (closer-mop, flexi-streams, float-features,
  trivial-gray-streams, uiop, com.inuoe.jzon usage example).
- `.kb` updates: `clos.md` (multi-arg dispatch, variadic generics, inline
  methods), `error-handling.md` (runtime type designator dispatch),
  `reader-features.md` (`#.` marker mode, reader labels, `*features*`
  interpreter variable), `asdf.md` (shim systems + jzon target), a new
  `gray-streams.md`; CLAUDE.md one-liners for the same.
- Run the `-Drontolisp.doc.fix=true` DocExamplesTest helper after writing
  examples.
