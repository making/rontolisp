# i21. cl-reexport computes exports the compile paths read too early

Difficulty: Medium

Measured 2026-11-04. clingon's `src/package.lisp` is `(defpackage :clingon (:use :cl))` plus top-level
`(cl-reexport:reexport-from :clingon.command)` calls: plain function calls computing `export` at load. The
compile paths read the whole program before running any of it, so a program's `clingon:getopt` fails with
`The symbol GETOPT is not external in the CLINGON package`.

With `clingon.command::` and `clingon.options::` spelled instead, clingon's `make-command`, `print-usage`
and `run` print the interpreter's output on the JVM and both WASM backends, so this is the only stop.

`PackageResolver.tryConsumeExport` takes a literal top-level `export` the same way; a literal
`reexport-from` of a package read earlier has everything it needs. Check other consumers of cl-reexport in
the Quicklisp cache before choosing a resolver rule over a tier 4 rewrite (`.kb/packages.md`, `.kb/asdf.md`).
