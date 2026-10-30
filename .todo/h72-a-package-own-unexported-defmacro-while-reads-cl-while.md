# h72. A package's own unexported `defmacro while` reads cl's `while`

Difficulty: Medium

Found 2026-10-31 probing `djula` (h59): its `cl-locale` dependency loads `arnesi`, whose
`src/flow-control.lisp:93` defines `(defmacro while (test &body body) ...)` in
`it.bese.arnesi` without exporting it (its `defpackage` has `;; #:while ; iterate`
commented out). `while` there resolves to rontolisp's `cl` extension, so the interpreter
stops: `DEFMACRO cannot redefine the standard operator WHILE`. SBCL interns
`it.bese.arnesi::while` (cl has no `while`). This is the residual h58 left
(`.kb/packages.md`, "But `cl`'s `while` YIELDS": fset's unexported `defmacro while` is the
same shape).

A definition of `while` in a package other than `cl-user` has to make the package's own
symbol win from there on (what `PackageRegistry.isClExtensionName` already does for an
exported or inherited one), on the interpreter and the compile paths alike. Decide what a
reference BEFORE the definition reads (SBCL: the package's symbol throughout).

Then probe further (`(ql:quickload "djula")`, interpreter): arnesi is large (a code
walker, a call/cc interpreter, mopp); record the next blocker in `.todo/152`.
