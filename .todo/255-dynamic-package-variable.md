# `*package*` is folded at resolution time, not dynamic

Difficulty: High -- it changes a canonical-shape invariant (`.kb/packages.md`:
"`*package*` -> quoted current package") that PackageResolverTest and the
per-backend suites pin directly, and it has to answer the same on all four
backends. Recommend starting with a Fable-class model.

Split out of `.todo/231` (2026-08-04). Not a blocker for anything that ships
today -- the two narrow adaptations below cover the known call sites -- but it
is the real cause behind both of them, and the next library that computes a
symbol from a `*package*` it captured across a function boundary will trip on
it again with an equally distant error message.

## The gap

`PackageResolver.resolveUnqualified` / `resolveQualified` rewrite a value-position
`*package*` into `(quote CURRENT-PACKAGE)`, where CURRENT is the package current
when the FORM WAS RESOLVED. Common Lisp's `*package*` is a dynamic variable read
when the form RUNS. The two agree at top level and disagree inside any defun that
outlives its file:

```lisp
;; alexandria-1/symbols.lisp, under (in-package :alexandria)
(defun maybe-intern (name package)
  (values (if package (intern name (if (eq t package) *package* package))
              (make-symbol name))))
```

`*package*` freezes to `ALEXANDRIA`, so `(alexandria:format-symbol t "+~A+" x)` --
documented as "a symbol interned in the current package" -- interns into
ALEXANDRIA for every caller. Silent until something reads the symbol back by
name; fast-http's `multipart-parser.lisp` generates 14 state constants that way
and then references them by its own package's spelling.

## What is in place instead (delete both when this lands)

- `eval.AlexandriaSymbols` rewrites that one form so the `t` branch uses the
  1-argument `intern` (whose "current package" IS read from the live resolver
  state). `ShimLibraries.rewriteComponentSource` tier.
- `LispEvaluator.expandMacroCall` runs a user macro's body with the macro's
  DEFINING package current (`UserMacro.definitionPackage`). That one is probably
  keeping even afterwards -- it is what a file-at-a-time compile does, and it
  removed a real interpreter/compile-path divergence -- but re-decide it here.

## Shape of the real fix (sketch, not a decision)

Give `*package*` a runtime value on every backend:

- resolve a value-position `*package*` to a genuine special variable read instead
  of a fold; `in-package` (today consumed by the resolver) also emits the runtime
  assignment, and the `%push-package`/`%pop-package` markers the compile path
  already brackets loaded files with emit save/restore;
- the value stays the package KEYWORD `find-package` answers, so `(print
  *package*)` keeps printing `CL-USER` and the existing pins hold;
- `(let ((*package* *package*)) ...)` must keep working: today both occurrences
  fold, and `LispEvaluator.evalLet` already has a `setCurrentPackage` hook for
  exactly this shape -- check it against the new model, it is the trickiest case.

Watch out for: `#.*package*` (sxql splices the value at read time), quoted data
(`'*package*` is the SYMBOL, not the package), and `PackageResolverTest`'s
`(QUOTE CL-USER)` assertions, which become the record of the OLD model.

## Consumer: rove (2026-08-15, `.todo/372` spike) -- now a milestone blocker

Rove keys its whole test registry on `*package*` read at RUN time inside its
own functions and macro expansions, and there is no adaptation that covers it:

- `(defun set-test (name test-fn) (pushnew name (slot-value (package-suite *package*) '%tests)) ...)`
  runs at each `deftest` (load time of the TEST file); the fold freezes it to
  `ROVE/CORE/SUITE/PACKAGE`, so every test registers under rove's own package
  and `(rove:run-suite *package*)` in the test file finds an empty suite ("✓ 0
  tests completed" -- observed).
- `setup`/`teardown`/`defhook` EXPAND to `(package-suite *package*)`; on the
  interpreter a `*package*` inside a macro expansion is never folded and reads
  "The variable *PACKAGE* is unbound" (observed), on the compile paths it folds
  to the CALLER's package -- right by accident, and the two backends disagree.
- `run-suite-tests` binds `(let* ((*package* (suite-package suite))) ...)`
  around the tests, the dynamic-binding shape.

The sketch above is the fix (dynamic value; `in-package` sets it at run time;
`load`/the spliced-file markers of `.todo/375` save/restore it; `defpackage`
untouched). The value stays the keyword `find-package` answers, which is also
what `.todo/376` dispatches on. Rove's `deftest`+`run-suite` round trip on all
four backends is the acceptance test to add here.
