# d48. `write-string` / `write-line` as function values write past a Gray stream on the compiled backends

Difficulty: Medium

On the interpreter `(funcall #'write-string "hello" o)` and `(funcall #'write-line "hello" o)`
with `o` a Gray stream instance reach the user's `stream-write-string`; on the JVM, WASM P1 and
the component the text goes to standard output and the method never runs (measured on all
three, with and without `:start` / `:end`). The Gray rewrite
(`GrayStreamsLibrary.rewrite`) only sees call position; `.kb/gray-streams.md` "Limits" records
`(funcall #'read-byte instance)` as the same gap. A nil or bad bound refuses on every backend
already (the built-in refuses before writing), so only valid calls are wrong:

```lisp
(defclass c (rontolisp:fundamental-character-output-stream) ())
(defmethod rontolisp:stream-write-string ((s c) str &optional (a 0) b) (print (list :got str a b)) str)
(funcall #'write-string "hello" (make-instance 'c))   ; interpreter: (:GOT "hello" 0 NIL); compiled: hello
(apply #'write-line (list "hello" (make-instance 'c)))
```

The function-value wrappers (`BuiltinFunctionWrappers`, the catalog that already forwards
keywords for `write-line` / `write-string`) are the seam: with the Gray protocol in the
program, their body should call the same dispatch helpers a call-position site is rewritten
to. Cover the other first-class stream operators the Limits line names while there
(`read-byte`, `read-char`, `read-line`, `write-byte`, ...), or split them off with numbers
for what each wrapper costs. Pin on all four backends through a case that is part of the
ci-spec corpus (the Gray library is only in the program when something in it names the
protocol).
