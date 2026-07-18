# jzon: remaining compile-path gaps (mutable strings, WASM bignum)

The real com.inuoe.jzon v1.1.4 loads and runs on the INTERPRETER
(`JzonE2eTest`, incl. the full README walkthrough). The language features it
forced are now wired through the compile path; what follows is the residue
that keeps the FULL library interpreter-only, after the 2026-07-18 sweep.

## DONE 2026-07-18 (this sweep; see git log for the earlier phases)

- Lite builtins on JVM+WASM via shared `LispMacroExpander` expansions:
  `mask-field`, `scale-float` (chunked-power-of-two, IEEE-exact),
  `fdefinition`, `file-position`/`file-length`/`pathnamep` (nil stubs),
  `make-broadcast-stream` (string-output-stream sink), `input-stream-p`/
  `output-stream-p` (= streamp), `stream-element-type` ('character),
  `class-of` (registry-driven tag dispatch + builtin type names),
  `slot-boundp`/`slot-makunbound` (literal slot name),
  `simple-condition-format-control`/`-arguments` (simple tags = slot 1 +
  registry classes by slot position), write-string `:start`/`:end`
  (`lowerWriteStringBounds`), replace nil bounds (or-wrapped),
  `char-name` (prelude defun), `%ieee754-*` (JVM only:
  `JvmIeee754Compiler`; WASM = documented numeric-model limitation).
  Wrapper entries for first-class use; unit tests both backends; ci-spec
  `lite-builtins-residue`; docs interpreter-only notes swept.
- `#.` on the compile path: marker read in `RontoLispCli.compileToFile` +
  `LoadInliner.spliceFile` (+ the CLI interpret path for main files),
  resolution per top-level form against the macro-time evaluator in
  `UserMacroExpander`, `%read-eval` identity emit in both compilers.
  ci-spec `read-time-eval-residue`. Playground Compile buttons keep the
  clear read error (documented).
- Corpus coverage pinned for the backend-free expansions: multi-parameter
  dispatch, variadic generic `&rest` forwarding, defgeneric inline
  `(:method ...)`, `:default-initargs`, with-slots write-through, defstruct
  options (JVM + WASM unit tests + ci-spec
  `clos-multi-dispatch-and-defstruct-options`).
- Gray streams on ALL backends: `GrayStreamsLibrary.process` pre-pass
  (splice + call-site rewrite onto `rontolisp::%gray-write-*-dispatch`),
  interpreter eager gray.lisp load for a defclass extending a Gray base
  class. ci-spec `gray-stream-instance-dispatch`; `.kb/gray-streams.md` +
  user guide.
- `uiop:add-package-local-nickname` consumed by `PackageResolver` for
  literal top-level calls (all backends); `defpackage :local-nicknames` +
  function pages written.
- Docs: missing-features table/prose refresh (tagbody/go, macrolet, typep,
  defstruct options, `#.`), defgeneric/defmethod/defclass/write-string/
  make-array/data-types pages, asdf-systems guide (built-in shim systems
  table + jzon entry), gray-streams guide, `.kb` updates (reader-features,
  asdf, clos, packages, error-handling, adjustable-arrays, gray-streams),
  `examples/asdf/jzon-demo.lisp` + README row (interpreter-only).

## Remaining

- **Fill-pointered/adjustable STRINGS on the compiled backends**: the
  interpreter's `make-array :element-type 'character` with
  `:fill-pointer`/`:adjustable` builds a mutable `LispString`; the compilers
  build a general character vector (fill-pointer surface works, but stringp
  is nil and it prints `#(...)`). Closing the gap needs a mutable-string
  runtime representation on BOTH backends (JVM strings are immutable
  `java.lang.String`; wasm-GC `TYPE_STRING` has an immutable len +
  `$str_bytes` data) and every string-consuming helper updated to accept
  it. Documented on the make-array page + `.kb/adjustable-arrays.md`.
- **jzon itself on JVM/WASM**: additionally blocked on the 64-bit/bignum
  numeric model for wasm-GC (eisel-lemire/schubfach do u64/u128 arithmetic;
  `(ash 1 128)` wraps today). The JVM backend has bignums, so a JVM-only
  jzon run would need just the mutable strings above plus
  `AsdfLibraryE2eSupport` wiring.

## Realization route for JVM/WASM (numeric shim + mutable strings)

Investigated 2026-07-18. Compiling the real jzon today dies at compile time
(not runtime): a `#.` marker in the eisel-lemire/schubfach power-of-ten tables
references a load-time special (`*%detailed-powers-of-ten-max*`) that is unbound
in the macro-time evaluator, so `RontoLispCli.compileToFile` throws
`... is unbound` before any code runs. Three blockers, split by shim-ability:

1. **Compile-time `#.` table crash** + 3. **wasm-GC bignum** -- BOTH are
   SHIMMABLE, cleanly, without touching jzon.lisp. jzon's contract with its
   numeric leaf modules is exactly 4 package-qualified functions:
   `com.inuoe.jzon/eisel-lemire:make-double` (mantissa exp10 sign -> double,
   float parse), `com.inuoe.jzon/ratio-to-double:ratio-to-double`,
   `com.inuoe.jzon/schubfach:write-float` / `:write-double` (float print).
   Replace `eisel-lemire.lisp`/`schubfach.lisp`/`ratio-to-double.lisp` with
   shim files backed by rontolisp-native float read/print (`%ieee754-*` + the
   standard reader/printer): the `#.` tables vanish (they live in those files)
   AND the u64/u128 arithmetic is no longer needed, so wasm-GC loses its extra
   handicap vs the JVM. Fits the existing `ShimLibraries` "leaf-module
   replacement" pattern. TRADEOFF: shimmed float output is NOT bit-identical to
   schubfach's shortest-round-trip string (`.todo/46` print-shape), so the
   stringify exact-match assertions must relax. (Bit-exact instead = implement
   real wasm-GC bignum, the heavy path -- deferred.)
2. **Mutable strings** -- NOT shimmable. It lives INSIDE jzon.lisp
   (`read-string` accumulator ~299-309/416+, string-output stream ~1220-1315),
   not in a swappable dependency, so avoiding it means either editing the target
   library (defeats "run the REAL jzon", the point of `JzonE2eTest`) or building
   the mutable-string runtime above. Confirmed empirically: on the JVM compile
   path `make-array :element-type 'character` is an immutable `String`, and the
   adjustable+fill-pointer accumulator pattern crashes at runtime
   (`ClassCastException: String cannot be cast to ArrayList` in `_rmSet`).

Plan: (a) numeric shim first -- low cost, kills blockers 1+3 at once and levels
WASM with the JVM; (b) THEN implement the mutable-string type (the single
biggest item -- both codegens + every string-consuming helper); (c) run the full
library on all 4 backends to flush any RESIDUAL compile-path gap not caught by
the phase-1/2 sweeps. Sufficiency of (a)+(b) is UNPROVEN until (c) passes -- the
two are the only KNOWN blockers, not a guarantee. **Next session: start on the
mutable-string runtime.**

- **Playground `#.`**: the browser frontend has no `UserMacroExpander`-style
  macro-time evaluator wiring for markers; Compile buttons keep the read
  error (documented in data-types.md).
- **`*features*` binding on the compile path**: still substituted at read
  time (a program binding the name breaks there); documented in
  data-types.md as accepted behavior.
