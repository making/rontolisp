# `make-load-form` — a literal object is dumped by its own method

**Invariant**: an OBJECT appearing as a literal in code the compile path is about to compile is
reconstructed by the form its own `make-load-form` method returns, not by serialising its slots. Done
once in `eval/LoadFormSubstituter`; both backends inherit it, no codegen of their own. It finishes
before any backend runs, so interpreter, JVM and both WASM backends see the same program.

## Where it runs

A macro can splice a LIVE object into its expansion (cffi's `defcfun` embeds a `#<FOREIGN-ENUM>`);
in code position it is self-evaluating (CLHS 3.1.2.1.3), and `JvmQuoteCompiler`/`WasmQuoteCompiler`
would dump it slot by slot (`Cannot quote: #<HASH-TABLE ...>`) while the interpreter never notices.
So it runs where the object appears and an evaluator still exists: `UserMacroExpander`, the compile
path's macro-time pass, with `LoadFormSubstituter.substitute` walking the WHOLE emitted program once
at the end of it -- a form reaches `result` by several routes (kept `eval-when` member,
`pax:defsection` residue, MOP splice).

## Default = the structural dump

- `make-load-form` is a cl-owned generic with **no system method** (`LispNames.MAKE_LOAD_FORM`,
  `PackageRegistry.CL_SYMBOLS`) -- the `print-object` pattern, so a `defmethod` in any package that
  uses cl joins the one generic.
- `LoadFormSubstituter` acts only on an instance whose type has a method
  (`LispEvaluator.hasMakeLoadFormMethodFor`: CLASS/TYPE specializer matching the type's own name, its
  class precedence list, or its `:include` chain; **an unspecialized default method does NOT count**
  -- it would claim every instance). Everything else the quote compilers dump by structure, which IS
  rontolisp's built-in default, written in Java: every `#S(...)` literal travels unchanged on every
  backend.
- `make-load-form-saving-slots` (prelude defun) spells that answer as data --
  `(%obj-new '<tag> 'v1 ... 'vn)` over `%obj-tag` / `%obj-slots`. Lite: `:slot-names` ignored (every
  slot travels); the object is rebuilt, not allocated-then-filled, so no self-reference.
- An object with an unspellable slot and NO method still fails with `Cannot quote: ...` -- the signal
  that the type wants a method.

## What is emitted

`(make-load-form obj)` runs in the macro-time evaluator; the object becomes
`(load-time-value <creation-form>)`, so the creation form runs ONCE at load time and every reference
shares the object. `hoistLoadTimeValues` turns it into a `%LOAD-TIME-VALUE-N` slot filled on first
use (`.kb/compiler-macros.md`).

- **init form** (the method's 2nd value):
  `(let ((%LOAD-FORM-OBJECT-1 <creation>)) <init> %LOAD-FORM-OBJECT-1)`. Every occurrence of that same
  object BY IDENTITY inside the init form is rewritten to the binding.
- **nested object**: the creation form is walked too; re-entering an object still being built is
  reported as a cycle, not looped.
- **inside a quoted constant**: `quote` is not descended into (its argument is data), but a constant
  CONTAINING such an object is rebuilt through `cons` and wrapped in `load-time-value`. Only the cons
  spine is rebuilt -- an object buried in a quoted ARRAY or hash table still fails the compile.
- One object dumped twice in a top-level form yields one creation form (identity-memoized); a program
  with no method pays one registry lookup, the walk never starts.

**Why it exists: cffi.** `defcfun` with a `defcenum` type embeds the foreign-type object, and
upstream `src/early-types.lisp` supplies a `make-load-form` method over `parse-type`/`unparse-type`.
Honoring it is the difference between the ecosystem's C bindings running interpreted and running from
a compiled `.class` (`.kb/cffi.md`).

## Tests

- `JvmLispCompilerTest#compileAndRunLiteralObjectDumpedByMakeLoadForm`,
  `#compileRefusesALiteralObjectWithNoMakeLoadFormMethod`, `#compileAndRunMakeLoadFormSavingSlots`
- `WasmLispCompilerIntegrationTest#literalObjectDumpedByMakeLoadForm`
- `LispEvaluatorTest#makeLoadFormSavingSlotsAnswersTheInstanceCreationForm`
- `ci-spec.yaml` case `make-load-form-literal-object`; `examples/jvm/cffi-sqlite.lisp` jvm leg
- Docs: `reference/functions/make-load-form{,-saving-slots}.md` (both language trees).
