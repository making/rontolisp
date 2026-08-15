# `*load-pathname*` has a run-time value per spliced file on the compile paths

Difficulty: Medium

Part of `.todo/372` (rove); pairs with `.todo/374`.

The interpreter binds `*load-pathname*`/`*load-truename*` around every file
`load`/`asdf:load-system`/`ql:quickload` reads. On the compile paths a spliced
file is not "loaded" at run time, so both are declared nil and stay nil
(`LispMacroExpander.injectMvSpillGlobal`, the load-context variables). That is
right for `(or *compile-file-truename* *load-truename*)` data-file lookups
(local-time), which the compile-time load resolves, and wrong for a library
that RECORDS the load pathname at definition time to correlate it later.

Rove is that library. Every `deftest`/`setup`/`defhook` goes through
`package-suite` -> `make-new-suite`:

```lisp
(defun make-new-suite (package)
  (let ((pathname (resolve-file (or *load-pathname* *compile-file-pathname*))))
    (when (and pathname (not (file-package pathname nil)))
      (setf (file-package pathname) package)))   ; hash: native-namestring -> package
  ...)
```

and `run` on a system walks `(asdf:component-pathname child)` for every source
file (`.todo/374`) through that hash (`system-packages`) to find the suites to
run. With `*load-pathname*` nil at deftest time nothing is recorded: for a
`:package-inferred-system` rove still runs the suite of the package named after
the system (its fallback), for a plain `defsystem` test system (`(defsystem
"foo/tests" :depends-on ("foo" "rove") :components ((:module "tests"
:components ((:file "main")))))` -- the most common shape) `(rove:run
"foo/tests")` runs NOTHING and reports "0 tests completed" on the JVM and both
WASM backends while the interpreter runs them all.

## Shape

`LoadInliner` already brackets a spliced system with `%begin-system`/`%end-system`
provenance markers. Add per-file markers (`%begin-file PATH TRUENAME` /
`%end-file`, or extend the system markers with the file list) that the
compilers lower to a statement-level assignment of the two globals plus a
save/restore around the file (nested loads inside a spliced file push; a
top-level form stays top-level -- this is `setq` bracketing, not a `let`), and
ONLY when the program reads either variable (the existing `loadContextVars`
gate); a program that never mentions them stays byte-identical. The value is
the same string the interpreter binds (the path `load` was called with;
truename = resolved against the enclosing directory), so `.todo/374`'s
`component-pathname` and this agree byte for byte -- pin that agreement, it is
the whole point. `*compile-file-pathname*`/`*compile-file-truename*` stay nil
(there is still no `compile-file`).

The compile-time evaluator (`UserMacroExpander`) already sees the file being
spliced for macro-time reads; unchanged.

Acceptance: a spliced file printing `*load-pathname*` at top level and from a
defun called later answers the file path / nil identically on all four backends
(`ci-spec` cannot carry a file, so the per-backend suites + `AsdfLibraryE2eSupport`
fixture); `RoveE2eTest` (`.todo/372`) runs a plain-`defsystem` test system's
suites on the JVM and WASM; `.kb/load-inliner.md` + `.kb/read-load-streams.md`
load-context paragraph and the `doc/{en,ja}/guides/asdf-systems.md` "load-context
variables are bound" bullet rewritten.
