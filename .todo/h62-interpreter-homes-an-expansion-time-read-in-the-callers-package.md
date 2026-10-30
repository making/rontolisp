# h62. The interpreter homes a symbol a macro READS at expansion time in the caller's package

Difficulty: Medium

Found 2026-10-30 (h35): with its `.asd` loading, `(ql:quickload "cl-pdf")` stops in
`font.lisp`'s load-time `(load-fonts)` with `The function GET-AFM-STRING is undefined`. cl-pdf's
AFM parser macro names each reader with
`(read-from-string (format nil "get-afm-~A" (string-downcase type)))` at EXPANSION time.

```lisp
(defpackage :p1 (:use :cl))
(in-package :p1)
(defun get-x (a) (list :x a))
(defmacro call-get (type arg) `(,(read-from-string (format nil "get-~A" type)) ,arg))
(defmacro call-get2 (type arg) `(,(intern (string-upcase (format nil "get-~A" type))) ,arg))
(defun use-it () (call-get "x" 1))
(defun use-it2 () (call-get2 "x" 1))
(in-package :cl-user)
(print (p1::use-it2))
(print (p1::use-it))
; SBCL 2.2.9, JVM: (:X 1) (:X 1)
; interpreter:     (:X 1), then "The function GET-X is undefined"
```

The interpreter expands `call-get` lazily, inside the call, with the macro's defining package
current (`.kb/packages.md`, "A user macro called from inside a FUNCTION BODY"): `*package*` is
`P1` there, so `intern` answers `P1::GET-X`. `read-from-string` answers a symbol with no package
(`.todo/156`), which is resolved later, in the package current when the expansion is
evaluated -- the caller's `CL-USER`. The compile paths expand at compile time in the file's
package and agree with SBCL.

Fix: the runtime reader homes an unqualified symbol in `*package*` as it reads, as `intern`
does -- or the lazy expansion resolves its result before the package swap ends. Check the
other runtime readers (`read`, the multi-argument `read-from-string`) for the same gap, pin the
repro on all four backends (a ci-spec case), and re-run `(ql:quickload "cl-pdf")`.
