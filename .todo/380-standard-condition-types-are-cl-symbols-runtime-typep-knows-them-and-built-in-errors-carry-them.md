# Standard condition types are `cl` symbols, runtime `typep` knows them, and built-in errors carry them

Difficulty: Medium

Part of `.todo/372` (rove); the typed half of `.todo/379`.

Rove's `signals` decides at RUN time, with the type in a variable:

```lisp
(defmacro signals (form &optional (condition ''error))
  `(let ((,condition-type ,condition))
     (typep (block nil
              (handler-bind ((condition (lambda (,c) (when (typep ,c ,condition-type) (return ,c)))))
                ,form nil))
            ,condition-type)))
```

Three things break `(ok (signals (foo) 'type-error))` today, all four backends
(spike 2026-08-15):

1. **The condition class names are not `cl` symbols.** `ClosRegistry` seeds 22
   classes (`condition serious-condition error simple-error simple-condition
   warning simple-warning style-warning parse-error type-error simple-type-error
   stream-error file-error arithmetic-error division-by-zero control-error
   program-error package-error cell-error unbound-variable undefined-function
   unbound-slot`), but only `error` is in `PackageRegistry.CL_SYMBOLS`: in a
   `(:use #:cl)` package `'type-error` reads as `MY-PKG::TYPE-ERROR`, prints
   that way, and two packages naming the same condition hold two symbols. Add
   the ANSI condition type names (the 22 plus the seeded-later ones: `end-of-file`,
   `reader-error`, `print-not-readable`, `storage-condition`, `floating-point-*`,
   `simple-*` -- whatever the registry seeds, keep the two lists one) to
   `CL_SYMBOLS`.
2. **A runtime type specifier naming a seeded class answers nil.**
   `(typep c 'type-error)` (literal) is T through `makeTypeTest`'s base-name
   class branch; `(let ((ty 'type-error)) (typep c ty))` is NIL --
   `%typep-runtime` does not resolve seeded condition classes (a user
   `define-condition` works both ways). Fix the runtime resolver; with (1) the
   spelling problem disappears too.
3. **Built-in errors are all `simple-error`.** `(handler-case (car 1) (type-error
   ...))` misses on every backend; the JVM message is the raw
   `ClassCastException` text. Map at the synthesis point (`.todo/379`'s
   landing pads and the interpreter seam): interpreter `LispEvalException`
   subclasses per family (type / arithmetic / division-by-zero / unbound-variable
   / undefined-function / index -> `type-error` per CLHS `aref`); JVM
   `ClassCastException` -> `type-error`, `ArithmeticException` -> `division-by-zero`,
   `IndexOutOfBounds` -> `type-error`, the "undefined function"/"unbound
   variable" runtime errors -> their classes; wasm: what is signaled already
   carries its class, traps stay traps. The messages become the same text on
   the interpreter and the JVM (a Java class name in a report is not an answer
   rontolisp should print -- `.kb/error-handling.md`'s uncaught-condition wording
   already made this point for the top level).

Acceptance: `(list 'type-error 'condition 'warning)` prints unqualified from a
user package on all four; the runtime `typep` shape above answers T; `(ok
(signals (car 1) 'type-error))` passes on the interpreter and JVM in
`RoveE2eTest` (`.todo/372`); ci-spec `condition-types` case; per-backend suites;
`.kb/error-handling.md` seeded-hierarchy paragraph and `.kb/packages.md`
`CL_SYMBOLS` count pins updated (ci-spec + the three backend tests pin the
cl symbol count -- move them together).
