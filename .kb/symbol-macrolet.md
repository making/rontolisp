# `symbol-macrolet` — one shared shadow-aware substitution, all four backends

`LispMacroExpander.expandSymbolMacrolet` lowers `(symbol-macrolet ((name expansion)...) body...)`
into a `progn` with every FREE reference replaced -- one walk (`substituteSymbolMacros`) shared by
the interpreter, the JVM and both WASM backends. **Do NOT add per-backend symbol-macro handling.**
Consumers: trivia `match`, dbi `driver.lisp`, mito `core/type.lisp`.

- **Shadow-aware**: every binder removes its names for its scope. `with-slots`/`with-accessors` run
  the same walk when they expand ([clos.md](clos.md)).
- **Data positions kept verbatim**: quote, declares, `#'name`, call heads, `case`/`typecase` keys,
  `go` tags, `block` names, `tagbody` atom tags, type tails, `eval-when` situations,
  `defmacro`/`macrolet` bodies, and the `defclass`/`defstruct`/`defgeneric`/`deftype`/
  `define-condition`/`defsetf` family. `typecase` is walked structurally.
- **A spec list is no call**: `print-unreadable-object`'s `(object stream ...)` and the lock macros'
  `(mutex)` are expressions element by element, the head included -- a call's walk keeps a head, so
  `(with-slots (inner) w (print-unreadable-object (inner s) ...))` left `inner` unsubstituted. The
  macro-expansion walk (`UserMacroExpander.expandAllLocated`) and `CallSiteWalk` know the same shapes,
  with the `(var form...)` ones (`with-open-file`, `do-symbols`, `with-hash-table-iterator`,
  `with-simple-restart`, `pprint-logical-block` ...), `with-slots`/`with-accessors` entries, `the`'s
  type and `handler-case`/`handler-bind` clauses as data: walked as calls, a variable named like a
  macro or a compiler macro expanded there (petalisp's `(print-unreadable-object (range stream))`,
  `range` having a compiler macro, became `(make-range 0 stream 1)`, 2026-11-07). Pin: ci-spec
  `macrolet-compiler-macro-restart-case`.
- `(setq name v)` whose substituted target is a cons becomes `setf`. `loop` and `prog`/`prog*` are
  expanded one step first (`expandLoop`/`expandProg`). Declarations directly in the body are
  DROPPED.
- **User macros must expand BEFORE substitution**, and the walk has no macro table: every path runs
  `UserMacroExpander.expandAll` first. The interpreter's arm (`LispEvaluator.expandSymbolMacrolet`,
  from `rareOperatorExpansion`, out of `evalCons`'s hot table, `LispEvaluatorHotMethodSizeTest`) does
  it once per call site, in the memo of `expandUserMacro`, in the package the code was read in.
  A macrolet in the body so expands before the walk (a local macro's `'s` used to stay unbound).
  `with-slots`/`with-accessors` take the same arm (`expandSlotVariables`), so a macro in the body sees
  the slot name, not the substituted `(slot-value ...)`.
- **At top level the body's forms are top-level forms** (CLHS 3.2.3.1, 2026-11-05): the compile path
  substitutes in `UserMacroExpander`'s walk, so a definition in the body is a top-level definition and a
  macro in a later form reaches it; the interpreter runs the expansion through `evalAtTopLevel`
  ([declarations-type-checks.md](declarations-type-checks.md), "An eval-when's position").
- Wiring: evaluator case, `Jvm/WasmExprCompiler.compileCons`, `FreeVarAnalyzer`,
  `rewriteLocalCalls`, `expandBuiltinMacro`.
- Lite: `defmethod` `&optional`/`&key` defaults are unwalked; a special-variable name is substituted
  anyway.

## `macroexpand` through `&environment`

A macro reads a symbol macro with `(macroexpand 'name env)`; the answer is the object the binding
holds, because the idiom writes into it (3bz `state-machine`, `deflate.lisp:67`: `.current-state`
is never referenced, only stored into by one macro and read by a later one).

- `expandAll`'s scope: `LispEvaluator.symbolMacroScope()`, a thread-local table of what is in
  scope at the form it walks (the global table when it has not narrowed or extended it). The
  `SYMBOL_MACROLET` case extends it with the raw binding objects, for the binding forms too (SBCL
  expands them at the reference, `((s 2) (u (mx s)))` reads 2). `expandBinderOfSymbolMacroName`
  narrows it only for a form binding a name in it, so a program with no symbol macro takes the
  ordinary walk: same binders and scoping as `substituteSymbolMacros`, which rewrites instead.
- `&environment` is not a `let` around the body any more: `UserMacro.environment()`, bound by
  `expandMacroCall` (`environmentAt`). Nil when no symbol macro exists anywhere; otherwise a
  `LispJavaObject` over `SymbolMacroEnvironment`, which every other reader ignores. A macro body
  runs with no walk scope, the null lexical environment; `macroexpand`/`macro-function`'s expander
  given an environment hand it on to the macro they call.
- The null environment holds the globals only (SBCL's `(macroexpand 's)` under a local `s` is `S`).
  An interpreted call reached while running has no walk: `environmentAt(site)` drops the globals
  its `Environment` binds lexically.
- A macro with `&environment` under a walk scope skips both per-call-site memos (`expandsPerScope`):
  one cons a macro spliced twice is two scopes.
- `(macroexpand 'sym env)` is not folded when `env` is computed: `expandAll` walks the body of a
  helper a macro calls before the macro-time evaluator sees it. It stays a call, and the prelude
  defun answers the symbol at run time.
- Lite: `macrolet` macros stay a dynamic table, visible through a nil environment too; a
  `with-slots`/`with-accessors` name is not in the table (they expand after `expandAll` has walked
  past them); `get-setf-expansion` ignores the environment; `JavaDeclarations`' pre-pass
  over a `java:` program expands macros with a nil environment.

## `define-symbol-macro` (global)

- Interpreter: `LispEvaluator.globalSymbolMacros` (written by `DEFINE-SYMBOL-MACRO` in
  `evalConsRareOperator`); `evalSymbolRef` consults it only after `env.lookupOrNull` is empty,
  `evalSetq` rewrites to `(setf expansion value)`. `symbol-value` does not see the name.
- Compile paths: `UserMacroExpander.harvestSymbolMacros` runs per top-level form after `expandAll`,
  registers and REMOVES definitions reachable through `progn`/`eval-when`; `Session.emitted`
  substitutes each form emitted after that. Activated by `usesDefineSymbolMacro`. A form a compiled
  `eval` runs is data to all of this; `_eval` has its own table ([eval-runtime.md](eval-runtime.md)).
- **The table holds RESOLVED names**, and the pass emits an untouched form in its original spelling.
  A form mentioning a symbol macro therefore emits its resolved spelling: until 2026-11-04 the
  substitution ran over the original one at the end of the pass, so a bare `SM` under
  `(in-package P)` missed `P::SM` and every binding's `(cffi:defcvar errno :int)` refused to compile
  (mmap's `check-posix`). The substitution comes before `requalifyShadowedClNames`: an expansion read
  in one package may land in a form of another that shadows a CL name in it.
- A definition's data stays verbatim, its expressions do not (2026-11-05, fset's `increment-atomic-series`
  in a `:initform`, `Cannot compile symbol`): a `defclass` or `define-condition` slot's `:initform`, a
  `:default-initargs` value, a `:report` that is a cons, and a `defstruct` slot's default.
  `UserMacroExpander.expandAll`'s `define-condition` arm reads the slot list as `defclass`'s, so a slot
  named like a macro is no call (fset's `(map :initarg :map)`).
- The walk knows a form that holds an expression where a call's head would be: `assert`'s places, datum and
  arguments and `check-type`'s string (sento's `(assert ... (format t "~a" (name act:*self*)))`, `Cannot
  compile symbol`), a `with-lock-held`/`with-mutex` spec (lparallel's lock slot, `#:LOCK530 is unbound`).
- A non-top-level definition is refused with `must be a top-level form`
  (`nestedDefineSymbolMacro`); the interpreter just registers it. A `case`/`ecase`/`ccase` key
  spelling the name is data, not a definition (serapeum's `internal-definitions.lisp:411`, which
  stopped the compile paths until 2026-11-07).
- **Trap**: `expandAll`'s `SETF` case must expand a user setf place FIRST and walk the expansion, or
  a COMPILER MACRO on the accessor renames the place and the accessor-keyed
  `define-setf-expander` misses it (cffi's `mem-ref`). Consumer: `cffi:defcvar` (`.kb/cffi.md`).

## An expansion that is a user setf place (i97, 2026-11-07)
A setf expander of an operator comes before its macro (CLHS 5.1.2.7), so a symbol macro whose
expansion is a call of an operator with a user setf expander is that expander's place. Expanding the
binding first, as the walk did for every binding, made it the macro's expansion -- for anaphora's
`slet`, `(symbol-macrolet ((it (internal-symbol-macrolet ...))) (setf it ...))`, a `setf` of a
`symbol-macrolet` form (`setf does not support place: SYMBOL-MACROLET`, generic-cl's list
iterators, on all four). Now `expandAll`'s `SYMBOL_MACROLET` case leaves such bindings as written
(`bindsUserSetfPlace`), walks the body, substitutes there and walks what the body became, so the
`SETF` case meets the place; every other `symbol-macrolet` keeps the old shape, substituted by the
backends. The interpreter's arm takes the walk's answer as it is when the walk substituted.
`LispEvaluator.expandUserMacroPlaces` (the interpreter's `setf` of a macro place) stops at an
operator with a user setf expander for the same reason: `(setf (m ...) v)` itself failed there.
`incf`, `decf`, `push`, `pushnew` and `pop` over a user setf place expand through the place's setf
expansion (`LispEvaluator.expandModifyUserPlace`, CLHS 5.1.3: the pushed item, the temporaries,
the store variable bound to the new value made from the ACCESS form, the store form), in the walk
and in the interpreter's arms (`modifyMacroExpansion`). They read the place by a call of its
operator before, which names no function for a place only its expander knows (`The function
CI-USP-FIRST is undefined` on all four), with or without a symbol macro. Not covered: `remf`,
`rotatef`, `shiftf`, `psetf` and a `define-modify-macro` over such a place.
ci-spec `a-symbol-macro-naming-a-user-setf-place-writes-through-its-expander`.

## Tests
`LispEvaluatorTest#evalSymbolMacrolet*`,
`#evalDefineSymbolMacroReadsAndWritesThroughTheExpansion`,
`JvmLispCompilerTest#compileAndRunSymbolMacrolet*` / `#compileAndRunDefineSymbolMacro`
(+ `…EmittedByUserMacro`), `WasmLispCompilerIntegrationTest#symbolMacroletForms` /
`#defineSymbolMacroForms`, `CffiSystemTest#defcvarReadsAndWritesARealCGlobal`, ci-spec
`symbol-macrolet-substitution-and-setf`, `define-symbol-macro-read-and-write`,
`macroexpand-environment-symbol-macros`.
