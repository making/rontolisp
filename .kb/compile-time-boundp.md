# `(boundp 'name)` is a compile-time fact (always on, both compile paths)

A compiled program is CLOSED: nothing in it can make a global variable spring into
existence at run time. So "is this name a global HERE?" is decided by the top-level
forms before the probe -- exactly the question `compiler/GlobalVarCollector` answers
for the backing stores, read in declaration order instead of as a whole-program set.
`compiler/CompileTimeBoundp.fold` reads it that way, replaces the probe with `t` /
`nil`, and collapses the top-level `if`/`when`/`unless` whose test it just decided.

**The interpreter is deliberately untouched.** There the question is live -- a REPL
form can define a global after the probe was read -- and `boundp`'s answer and error
text are pinned.

## Why it is worth a pass: the idiom costs twice

`(unless (boundp '+k+) (defconstant +k+ v))` is the portable redefinition-safe
`defconstant`; chipz spells 12 of them in the zlib row through its own
`define-constant` macro, and `unless (boundp` appears in yason, iterate, mgl-pax,
global-vars and slime in the local dist alone. Both costs are real:

- **The probe holds the eval runtime open.** `boundp` sits in the `usesEval`
  OR-chain of both backends (`JvmLispCompiler` / `WasmLispCompiler`), which emits
  `_eval`/`_lookup`/the global mirror AND forces EVERY arity dispatcher
  (`.kb/eval-runtime.md`).
- **The guard hides the definition from the tree-shaker.** Wrapped in an `unless`,
  the `defconstant` is not a top-level definer, so `eval/LibraryDefunPruner` cannot
  drop it when nothing reads it -- a 256-entry `(unsigned-byte 32)` CRC table stays
  in the artifact of a program that never checksums.

The second is why the fold ALSO runs before the shaker and not only inside the
compilers; see the call sites below.

Measured on `size-report/programs/zlib/zlib.lisp` at `--optimize=size`:

| zlib | before | after |
| --- | ---: | ---: |
| WASM (`.wasm`) | 107,628 | 105,393 (**-2.1%**) |
| JVM (`.class`) | 193,722 | 181,768 (**-6.2%**) |

**Both deltas are the SHAKER half, not the gate half, and that is the finding worth
carrying.** chipz's guarded constants include two 256-entry `(unsigned-byte 32)` CRC
tables the gzip path never reads; unwrapped they are top-level definers and
`LibraryDefunPruner` drops them. On WASM that is 2,048 bytes of data segment plus the
seven globals holding them; on the JVM the same two tables cost 4,975 bytes of `_top$0`
bytecode and ~7 KB of constant pool with it -- a literal table is worth far more than
its bytes on that backend. Nothing else in either artifact moved: all 354 JVM methods
are byte-for-byte the same size except `_top$0`.

The gate half buys zlib nothing on EITHER backend, for two different reasons, and both
are worth knowing before re-measuring: WASM had already lost the eval runtime (todo-315
folded this one idiom shape and nothing else in chipz probes), and the JVM keeps it
whatever happens here, because chipz's own `apply` holds that backend's deliberately
wide gate open (`.kb/eval-runtime.md`). What the gate half is worth shows on a program
whose ONLY eval trigger is the probe: `(defvar *gx* 1) (print (boundp '*gx*)) (print
(boundp '*gnope*))` at `--optimize=size` goes **20,702 -> 559 bytes**, byte-identical to
the same program with the two answers written out. That shape -- a probe todo-315's
first-occurrence proof could not discharge -- is the one this pass added.

## Where it runs, and why in four places

| call site | packages resolved | what it buys |
| --- | --- | --- |
| `RontoLispCli.compileRecorded`, before `LibraryDefunPruner.prune` | no | the tree-shaker sees top-level definers |
| `RontoPlayground.frontend`, before the same shake | no | the browser path stays equivalent |
| `JvmLispCompiler.compile` / `WasmLispCompiler.compile`, after `PackageResolver` | yes | the `usesEval` gate scan finds no `boundp`; a direct compiler invocation is equivalent |

The compiler runs are idempotent for a CLI-driven compile (the probes are already
gone) and are what decides the cases only canonical spellings can decide.

On WASM that run sits AFTER `NoWasiFilesystemStubs.rewrite`, for the same reason the
gate scans do: the fold refuses a program that can `eval`, and clack's DEAD `(read)` /
`(eval)` file loader IS such a program until that rewrite takes it out. It buys no
measured bytes on any Worker today (their gates are held open elsewhere) -- it is there
so the fold reads the program that is actually compiled rather than the one before the
rewrite.

**Before resolution only the "unbound" direction is answered.** `+k+` in two packages
is one string there, so a same-named definer elsewhere may BLOCK a fold -- which is
the safe direction -- but must never assert a binding. The `packagesResolved`
parameter is that switch: with it false, `Names` also matches on the unqualified
member name and the `t` answer is withheld.

## The soundness gate is free

The fold is unsound exactly when a global can appear at run time: `eval`, `load`,
`--dynamic`, and -- since progv landed on the compile paths (todo-423) -- `progv`,
whose lowering can bind a runtime-named symbol in the eval env mirror. The first
three each force the full eval runtime on their own, so for them the condition that
makes the fold unsound is the same one that makes it pointless -- `fold` returns the
program untouched and loses nothing. `progv` does NOT force that runtime, so its gate
entry genuinely costs the fold in a progv-using program; that is the price of the
binding being runtime-created. (`set` and `(setf (symbol-value ...))` would belong in
the list too; this language has neither. If either is ever added, it goes in the
gate.)

This gate is also what retired todo-315's **forgery carve-out** -- that fold refused a
guard whenever any earlier STRING literal contained the name, on the theory that a
`(set (intern "..."))` could forge the binding. With no `set` in the language the only
forger left is `eval`, and the gate refuses the whole program for that. Whole-program
and exact beats per-name and heuristic: a library that merely mentions its own constant
in a docstring is no longer undecidable.

## What is decidable, and what deliberately is not

`nil`, `t` and every keyword are self-bound on every backend and fold to `t` without
looking anything up. For a quoted ordinary symbol:

- **`t`** when a STRICTLY EARLIER top-level form binds it unconditionally -- the form
  itself is `(defvar/defparameter/defconstant NAME value)` or `(setq NAME ...)`, or a
  `progn` of those. Nothing deeper counts: `GlobalVarCollector`'s scan is deliberately
  blind to lexical scope and to conditionals, which is the right blindness for
  refusing a fold and the wrong one for asserting a binding -- `(if (foo) (defvar *x*
  1))` binds nothing on the untaken branch and `(let ((x 1)) (setq x 2))` assigns the
  lexical slot, never the global.
- **`nil`** when no earlier top-level form binds it (by the wide scan) AND the current
  form either does not bind it at all or the probe sits on that form's straight-line
  evaluation PREFIX. The prefix is what makes the guard idiom decidable: the probe of
  `(unless (boundp 'x) (defconstant x v))` runs before the definition two tokens
  later. The flag is cleared by passing any definer and by entering a deferring or
  repeating head, so `(dotimes (i 2) (print (boundp '*x*)) (defvar *x* 1))` -- where
  the second iteration sees it bound -- keeps its probe.
- Inside a **deferred body** (`lambda`/`defun`/`defmethod`/`flet`/`labels`/...) the
  written position proves nothing: the body runs whenever it is called. Only the
  whole-program question is answered there, and only in the `nil` direction -- a name
  no form anywhere makes global has the same answer at every moment.

Never answered, either way:

- a **`cl` symbol** -- some are born bound (the standard stream variables are seeded
  into the `_genv` mirror), and which ones is the backends' business;
- a name a **`(defvar x)` with no value** names -- it proclaims `x` special and binds
  nothing, so a later `(let ((x ...)) ...)` is what makes `boundp` true for its
  extent;
- a name in any **`special` declaration** (`declaim`/`proclaim`/`declare`), for the
  same reason. A `declaim` that only declares a TYPE binds nothing and does not block
  the guard that follows it -- chipz's `+crc32-table+` is exactly that shape;
- a **computed designator** (`(boundp (intern ...))`), which is not a literal at all
  and keeps the runtime probe, and with it the eval runtime.

## What the fold leaves behind

Deciding the probe is not the whole job -- what it was the test OF has to go with it,
or the guard's other arm holds the gate open by a different OR-chain member.

At the TOP LEVEL the surviving branch is spliced INTO the top-level list, which is what
makes the definition a top-level definer again: `(unless nil BODY...)` becomes
`BODY...`, `(when nil ...)` / `(unless t ...)` are dropped, `(if t a b)` / `(if nil a
b)` become the branch taken.

Anywhere else the form collapses in place, and only on a cons this pass actually
rewrote -- so it never becomes a general constant folder (`(print (if nil 1 2))` written
by hand is left exactly as it stands). `if` / `when` / `unless` / `not` / `null`, plus
the FIRST argument of an `and` / `or` (a later literal proves nothing about the
arguments before it, which still run). That in-place half is not a nicety: the OTHER
portable spelling of the same idiom puts the guard in the initform --

```lisp
(defconstant +k+ (if (boundp '+k+) (symbol-value '+k+) 1))
```

-- and cl-ppcre, cl-who, flexi-streams, cl-base64 and cl-unicode all define
`define-constant` that way (alexandria reads the probe through `not`, cl-json through
`and`). Decide the probe and stop, and the dead `(symbol-value '+k+)` arm is still in
the program: `symbol-value` is its own arm of the `usesEval` chain, so the whole
interpreter stays. `(defconstant +k+ (if (boundp '+k+) (symbol-value '+k+) 1)) (print
+k+)` at `--optimize=size` is **20,667 bytes without the collapse and 4,734 with it** --
byte-identical to the bare definition. That is the cl-ppcre-shaped half of the win, and
it reaches every Worker that routes.

A `cond` guard is not collapsed; it is left for the ordinary macro expansion, which runs
after both the gate scan and the shaker, so that spelling still pays. Nothing in the
local dist writes it.

Two consequences worth knowing:

- `ClRedefinitionWarnings` is about FUNCTION redefinition and never looked at
  constants, so a constant that now looks unconditionally defined changes nothing
  there. The fold can only REMOVE a definition (a second guard for the same name folds
  to `t` and its `defconstant` goes away), never add one.
- The pass owes both halves of `.kb/source-positions.md`: it hands back the SAME list
  when nothing was decidable, rebuilds through `LispCons.rebuilt`, and every cons on
  the path down to a folded probe takes the position of the one it replaces
  (`SourceProvenance.inherit`).

## `fboundp` is deliberately out (the re-evaluation trigger)

It is the same OR-chain arm and the same idiom shape, but its answer is the FUNCTION
registry rather than the globals table -- and that registry includes every name the
backends implement as a built-in, which is not a set this pass has. Deciding
`(fboundp 'car)` wrongly is a miscompile, and the only cheap half (a name no `defun`
and no built-in defines) is not an idiom anything in the dist writes. Revisit if a
shared "every callable name" set ever exists for another reason.

## Pins

- `compiler/CompileTimeBoundpTest` -- every direction and every refusal above,
  including the pre-resolution restriction and the unchanged-list identity rule.
- `JvmLispCompilerTest#theDefineConstantGuardCompilesToTheBareDefinition` and
  `WasmLispCompilerIntegrationTest#aLiteralBoundpCostsNothingWhileAComputedOneStillCarriesTheEvalRuntime`
  -- a decided probe compiles BYTE-IDENTICALLY to its answer, the guarded definition
  to the bare one, and a computed probe still differs. The size half of that pin is
  WASM-only on purpose: the JVM gate is deliberately wide and its class shaker is what
  trims the runtime, so gate state is not readable from the class size there
  (`.kb/eval-runtime.md`). It is also read off the SHAKEN module -- an unshaken one
  carries every runtime helper (126 KB) whatever the gate says, which is what makes a
  ratio assertion on the plain module meaningless.
- `JvmLispCompilerTest#compileAndRunBoundp` /
  `WasmLispCompilerIntegrationTest#boundpChecksTheGlobalVariableNamespace` and
  `standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi` -- the runtime
  `boundp` answers, unchanged. The stream-variable case is `cl` symbols throughout, so
  it is the one that keeps the runtime arm itself exercised.

**`ci-spec.yaml` covers the UNFOLDED path, not this one.** The E2E driver concatenates
every case into one program, and one of them calls `eval` -- so the gate above trips
and the whole spec compiles with the fold off on all four backends. That is worth
knowing twice over: it is why the spec's `symbol-runtime-api` answers are unchanged by
construction, and it is a re-evaluation trigger -- if the `eval` case ever leaves the
spec, the driver starts exercising the fold instead and those answers must still be
identical.
