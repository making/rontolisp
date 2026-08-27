# `make-load-form` — a literal object is dumped by its own method

**The invariant**: an OBJECT that appears as a literal in code the compile path is about
to compile is reconstructed by the form its own `make-load-form` method returns, not by
serialising its slots. The substitution happens once, in `eval/LoadFormSubstituter`, and
both backends inherit it with no codegen of their own.

CLHS 3.2.4.4 gives this exactly one protocol, and it is not a cffi feature: `defstruct`'s
`:make-load-form-fun`, cl-ppcre's compiled scanners and any library that memoizes a CLOS
object into a macro expansion want the same thing. What forced it was cffi — see the last
section.

## Where the object comes from, and why the substitution lives in the macro pass

A macro splices the LIVE object into its own expansion:

```lisp
(cffi:defcenum status (:ok 0) (:bad 1))
(cffi:defcfun ("abs" c-abs) status (n :int))
;; expands to
(defun c-abs (n)
  (let ((#:g1 n))
    (cffi:translate-from-foreign (cffi-sys:%foreign-funcall "abs" (:int #:g1 :unsigned-int) ...)
                                 #<CFFI::FOREIGN-ENUM STATUS>)))
```

That `#<FOREIGN-ENUM>` is an ordinary `LispInstance` sitting in code position, where it is
self-evaluating (CLHS 3.1.2.1.3). `JvmQuoteCompiler` / `WasmQuoteCompiler` reach it through
`compileLiteralInstance` and dump it slot by slot — and an enum's slots hold hash tables,
which nothing can spell, so the compile died with `Cannot quote: #<HASH-TABLE ...>`. The
interpreter never noticed: the object is live there, and so it is in the native binary.

The substitution therefore belongs where the object appears and an evaluator still exists,
which is `UserMacroExpander` — the compile path's macro-time pass. By the time a quote
compiler sees a form there is no evaluator left to ask. `LoadFormSubstituter.substitute`
runs over the WHOLE emitted program, once, at the end of that pass: a form reaches
`result` by several routes (a kept `eval-when` member, a `pax:defsection` residue, a MOP
splice) and any of them can carry such an object.

## The default is the structural dump, and stays

`make-load-form` is a cl-owned generic function with **no system method** — the
`print-object` pattern (`LispNames.MAKE_LOAD_FORM`, `PackageRegistry.CL_SYMBOLS`). A
`defmethod` written inside any package that uses cl therefore joins the ONE generic every
consumer reaches.

`LoadFormSubstituter` acts only on an instance whose type has a method
(`LispEvaluator.hasMakeLoadFormMethodFor` — a CLASS/TYPE specializer matching the type's
own name, its class precedence list or its `:include` chain; an unspecialized default
method does not count, because it would claim every instance in the program). Everything
else is left exactly as it was, and the quote compilers dump it by structure.

That is deliberate, and it is what CLHS licenses for `structure-object`: **rontolisp's
built-in default `make-load-form` for an instance IS the structural dump the quote
compilers implement**, written in Java rather than in Lisp. Two consequences worth
keeping:

- every `#S(...)` literal in every existing program travels unchanged, on every backend,
  whether or not the program defines any macro at all;
- `make-load-form-saving-slots` (prelude defun) spells that same answer as data —
  `(%obj-new '<tag> 'v1 ... 'vn)` over `%obj-tag` / `%obj-slots` — so a library method
  that delegates to it (cl-ppcre's `charmap` / `charset`) gets the built-in answer instead
  of the error the old stub signalled. Lite: `:slot-names` is ignored (every slot
  travels), and the object is rebuilt rather than allocated-then-filled, so it cannot
  carry a reference back to itself.

An object with an unspellable slot and NO method still fails the compile with
`Cannot quote: ...`. That failure is the signal that the type wants a method, and
`JvmLispCompilerTest.compileRefusesALiteralObjectWithNoMakeLoadFormMethod` pins it.

## What the substitution emits

For each object, `(make-load-form obj)` is called in the macro-time evaluator and the
object is replaced by

```lisp
(load-time-value <creation-form>)
```

`load-time-value` is the point, not decoration: CL evaluates a dumped literal's creation
form ONCE at load time and every reference shares the one object. The compile path's
`hoistLoadTimeValues` turns that into a `%LOAD-TIME-VALUE-N` slot filled on first use
(`.kb/compiler-macros.md`), so `c-abs` parses its foreign type once rather than per call.

Three shapes beyond the plain one:

- **an init form** (the method's second value) runs against the freshly created object:
  `(let ((%LOAD-FORM-OBJECT-1 <creation>)) <init> %LOAD-FORM-OBJECT-1)`. CLHS words the
  init form as referring to "the object being created", and it does that by carrying the
  object LITERALLY, so every occurrence of that same object (by identity) inside the init
  form is rewritten to the binding.
- **a nested object** — the creation form itself is walked, so an object whose method
  mentions another dumpable object resolves too. Re-entering an object that is still
  being built is a cycle and is reported as one rather than looping.
- **inside a quoted constant** — `quote` is not descended into, because its argument is
  data; but a constant that CONTAINS such an object cannot be spelled by quoting at all,
  so it is rebuilt through `cons` and wrapped in `load-time-value` (which restores the
  "one shared constant" semantics quoting had). Only the cons spine is rebuilt: an object
  buried inside a quoted ARRAY or hash table still fails the compile.

One object dumped twice in the same top-level form yields one creation form
(identity-memoized), and a program with no `make-load-form` method at all pays a single
registry lookup — the walk never starts.

## Cross-backend

The substitution finishes before any backend runs, so the interpreter, the JVM backend
and both WASM backends see the same program. The interpreter reaches none of this on its
own (its literal is the live object), which is exactly why the answers agree.

## Tests

| what | where |
|---|---|
| a spliced literal object with an unspellable slot compiles and runs; the no-method case still refuses; `make-load-form-saving-slots` round-trips | `codegen/jvm/JvmLispCompilerTest#compileAndRunLiteralObjectDumpedByMakeLoadForm`, `#compileRefusesALiteralObjectWithNoMakeLoadFormMethod`, `#compileAndRunMakeLoadFormSavingSlots` |
| the same program on wasm-GC | `codegen/wasm/WasmLispCompilerIntegrationTest#literalObjectDumpedByMakeLoadForm` |
| the generic dispatches and the prelude answers the creation form | `eval/LispEvaluatorTest#makeLoadFormSavingSlotsAnswersTheInstanceCreationForm` |
| all four backends, one output | `ci-spec.yaml` case `make-load-form-literal-object` |
| the ecosystem case end to end | `examples/jvm/cffi-sqlite.lisp`, `jvm` leg |

## Why it exists: cffi

A `defcfun` whose return or argument type is a `defcenum` — which is most of a real
binding's entry points, and cl-sqlite's every one — embeds the foreign-type object, and
upstream makes that legal with

```lisp
(defmethod make-load-form ((type foreign-type) &optional env)
  `(parse-type ',(unparse-type type)))
```

in `src/early-types.lisp`. Honoring it is the whole difference between the ecosystem's C
bindings running interpreted and running from a compiled `.class` (`.kb/cffi.md`).

Docs: `reference/functions/make-load-form.md` and
`reference/functions/make-load-form-saving-slots.md` (both language trees).
