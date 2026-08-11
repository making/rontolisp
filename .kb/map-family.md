# The `map*` family over N lists

**Invariant**: `mapcar`, `mapc`, `mapcan`, `maplist`, `mapcon` and `mapl` each take a
function plus ANY number of lists, on ALL FOUR backends, in call position AND as a
first-class value. The function is called with one argument per list and the walk stops
as soon as the SHORTEST list runs out (CL's termination rule). A call with no list at
all is rejected -- never taken for a one-list call.

The family is the canonical place a per-backend divergence hides in silence: the count
is static in call position, so a backend that compiles only `(op f l)` and ignores the
rest of the argument list produces a plausible WRONG list rather than an error. That is
exactly what shipped until `.todo/218` (see "History" below).

## The three implementations, and why there are three

| operator | call position | first-class value |
| --- | --- | --- |
| `mapcar` / `mapc` / `mapcan` | per-backend inline emitter | shared wrapper |
| `maplist` / `mapcon` / `mapl` | shared macro expansion | shared wrapper |

1. **`LispMacroExpander.expandMapFamily`** -- the shared N-list lowering behind
   `maplist`/`mapcon`/`mapl`, reached identically by `LispEvaluator.evalCons` and both
   `compileCons`es, so those three have exactly one implementation for every backend.
   Two axes parameterize it: `tails` (the function receives the successive cdrs
   themselves, not their cars) and `MapAccumulation` (`COLLECT` a fresh list /
   `CONCATENATE` the values / `DISCARD` them and answer the FIRST list). It emits

   ```lisp
   (let ((#fn F) (#l0 L0) (#l1 L1))       ; left-to-right: function, then lists
     (if (listp #l0) nil (error ...))     ; every list position is guarded
     (if (listp #l1) nil (error ...))
     (do ((#acc nil)                       ; COLLECT / CONCATENATE only
          (#c0 #l0 (cdr #c0)) (#c1 #l1 (cdr #c1)))
         ((or (atom #c0) (atom #c1)) RESULT)
       BODY))
   ```

   With ONE list the end test is the bare `(atom #c0)`, so the single-list expansion
   the backends compiled before the widening is unchanged. `do` evaluates the step
   forms before the test, so `cdr` is never applied to the atom that ends the walk.

2. **Per-backend inline emitters** for the three hot members: `Jvm/WasmMapcarCompiler`,
   `Jvm/WasmMapcCompiler`, `Jvm/WasmMapcanCompiler`. Each keeps one slot per list,
   branches to the exit as soon as ANY cursor is not a cons, pushes one `car` per list
   and calls `_invoke_<nLists>` / `dispatch_<nLists>` (so `ctx.indirectCallArities` must
   be told `nLists`, not 1 -- a stale `1` there is what made a two-list `mapc` trap on
   WASM). **Unless the designator is one the compiler can READ**: a literal `#'name` /
   `'name` naming a function that takes `nLists` arguments is called DIRECTLY instead,
   through `Wasm/JvmDesignatorCall` -- the same decision `funcall`, `reduce` and `sort`
   make, and the arity is then not registered at all. Why, and what it is deliberately
   not applied to: `.kb/optimize-dead-code-elimination.md`, "A designator the compiler
   can READ never enters `valueFuncIds`". `mapc` keeps its first list in a slot of its own because it is the return
   value; `mapcan` walks the list slots directly, since only the concatenation survives.
   The interpreter's counterparts are `LispEvaluator.mapFamilyValues` (the shared walk)
   plus `mapValues`/`mapForEffect`/`mapcanValues` (the three finishers) --
   **the interpreter is the reference the compile backends are diffed against**, so widen
   it first.

3. **`BuiltinFunctionWrappers.mapFamilyWrapper`** -- the value path, where the list count
   is a RUNTIME property, so a fixed-arity wrapper cannot forward the extra lists. One
   shape for all six, parameterized by the same two axes:

   ```lisp
   (lambda (f l &rest more)
     (if (null more)
         (op f l)                                   ; one list: the primitive
         (do ((ls (cons l more)) (acc nil))          ; N lists: shortest-list walk
             ((member nil ls) RESULT)
           BODY
           (setq ls (mapcar (lambda (x) (cdr x)) ls)))))
   ```

   All six are injected UNGATED, like every other non-`REFERENCE_GATED_FUNCTIONS`
   wrapper. Widening the five thin `binary(op)` wrappers into these `do` loops costs a
   trivial program ~10 KB of `.class` / ~13 KB of `.wasm` (measured: 180 KB -> 190 KB,
   292 KB -> 305 KB), and `--optimize` strips every unreferenced wrapper again (22 KB
   `.wasm` / 3.6 KB `.class`). Gating them on `referencesFunctionValue` instead would
   trade that default-mode size back for a silent one-list answer whenever the gate
   cannot see the reference -- the exact failure mode this whole file exists to prevent.

   The inner `mapcar`s are single-list, so they compile as the primitive. `(member nil
   ls)` is "some list is exhausted" for PROPER lists -- an improper list is not caught
   here, unlike the `atom` test the call-position lowering uses. The interpreter does not
   use these wrappers: its own built-ins ARE the function objects, which is why
   `maplist`/`mapcon`/`mapl` needed `defineFunction` registrations of their own (they are
   macro-expanded in call position, so without them `#'maplist` answered "The function
   MAPLIST is undefined" while both compile backends wrapped it happily).

## Non-list arguments and arity

Every list position is guarded, not just the first: a non-list (e.g. a string) signals
`<NAME>: argument is not a list ... (use map for strings/vectors)` in the interpreter
and on the JVM, and traps (`unreachable`) on WASM. `nil` is a valid empty list. Use
`map` for strings/vectors.

A call with no list -- `(mapcan #'list)` -- is a `<NAME> expects at least 2 arguments`
error: `LispEvaluator.requireMapLists` for the interpreter and the built-in function
objects, `expandMapFamily` for the shared three, and an `UnsupportedOperationException`
from the emitter for `mapcar`/`mapc`/`mapcan` (a compile error, since the count is
static in call position).

## Deliberate divergences from CL

- `mapcan`/`mapcon` concatenate with non-destructive `append`, not `nconc`. This is
  documented user-visible behavior (`doc/*/reference/functions/mapcan.md`), not an
  oversight; a caller that relies on the argument lists being spliced sees fresh conses
  instead.
- `every`/`some` are a different family (they take SEQUENCES, so each argument is coerced
  to a list first, and there is no listp guard). They take any number of sequences too
  since `.todo/219` -- `LispMacroExpander.expandEverySomeFamily` plus
  `BuiltinFunctionWrappers.everySomeWrapper` -- but the lowerings are their own; nothing
  here is shared with them beyond the shape.

## Pinning tests

- ci-spec (all four backends): `mapcar-as-a-first-class-value-over-many-lists`,
  `mapc-over-many-lists`, `mapcan-over-many-lists`, `maplist-over-many-lists`,
  `mapcon-over-many-lists`, `mapl-over-many-lists`.
- `LispEvaluatorTest`: `mapFamilyOverMultipleLists`, `mapFamilyAsValuesOverMultipleLists`,
  `mapFamilyRejectsACallWithNoList`, `mapFamilySignalsErrorOnNonList`,
  `mapcarAsValueOverMultipleLists`.
- `JvmLispCompilerTest`: `compileAndRunMapFamilyMultipleLists`,
  `compileAndRunMapFamilyAsValuesOverMultipleLists`,
  `compileAndRunMapcarAsValueOverMultipleLists`.
- `WasmLispCompilerIntegrationTest`: `mapFamilyMultipleListsCompilesAndRuns`,
  `mapFamilyAsValuesOverMultipleListsCompilesAndRuns`,
  `mapcarAsValueOverMultipleListsCompilesAndRuns`, `mapFamilyTrapsOnNonList`,
  `applyUsingWrapperReachedByFuncallCompilesAndRuns` (see the WASM `apply`-gate note below;
  each of its assertions must be the ONLY form in its program).

## History

`#'mapcar` as a VALUE dropped every list but the first on both compile backends until the
alexandria enablement pass (2026-07-30) walked into it: `alexandria:mappend` is `(apply
#'mapcar function lists)`, so it answered `(1 2)` where the interpreter answered `(1 3 2
4)` -- no error, just a wrong list (`.kb/asdf.md`, alexandria entry). That pass fixed
`mapcar` alone and left `.todo/218` for the rest of the family, which was worse: one form,
`(mapc f '(1 2) '(3 4))`, had THREE answers -- an arity error in the interpreter, a
silently empty walk returning `(1 2)` on the JVM, an `unreachable` trap on WASM -- and
`mapcan`/`maplist`/`mapcon` silently ignored the extra lists in CALL position too. Closing
it widened all three implementations above at once rather than making the wrong answers
loud and stopping there, because the wrong answers only existed for a count CL specifies.

## The wrapper's `apply` and the WASM emission gate

`mapFamilyWrapper` forwards a RUNTIME number of lists, so its body calls `apply`. On WASM
the `apply` runtime (`_apply`, pulled in with the eval runtime) is gated on `usesEval`,
which scans the SOURCE program -- and the wrappers are injected AFTER that scan. So a
program that took `#'mapcar` as a value but used `apply` nowhere else got a wrapper calling
an `_apply` that had degraded to a nil-answering stub: `(funcall #'mapcar #'list '(1 2)
'(3 4))` answered `(NIL NIL)` where the interpreter and the JVM answered `((1 3) (2 4))`.
Not a trap -- the same silent-wrong-list failure mode this file exists to prevent, and it
survived `.todo/218` because no test spread the lists across a `funcall` (the existing
value-path cases all used `apply`, which forces the gate on by itself).

`BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` is the set of wrappers whose bodies call
`apply` -- the `map*` six, `every`/`some`, and `funcall` itself -- and
`referencesApplyingWrapper` answers "is one of them reachable as a first-class value here".
`WasmLispCompiler`'s `usesEval` consults it. **Any new wrapper whose body calls `apply` must
join that set**, and any backend that gates a runtime on a program scan has the same trap:
the scan does not see the injected wrappers.
