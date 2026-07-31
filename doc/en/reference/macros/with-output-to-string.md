# with-output-to-string

`(with-output-to-string (stream) body...)`

Binds `stream` to a string output stream, evaluates the body forms, and returns everything written to the stream as a string. `princ`, `prin1`, `print`, `terpri`, `fresh-line`, `write-line`, `write-char` and `write-string` accept the stream as their optional stream argument, and `format` accepts it as the destination; each call appends to the stream. Works in all three backends.

```lisp
(with-output-to-string (s)
  (princ "1 + 2 = " s)
  (princ (+ 1 2) s)) ; => "1 + 2 = 3"
```

Naming the bound variable `*standard-output*` redirects the whole
stream-argument-less print family for the extent of the body -- including
inside called functions, and including `format` with the `t` destination --
because those calls read the current (dynamically bound) value of
`*standard-output*` at call time. The same redirect works for any `let` that
binds `*standard-output*` to an output stream.

```lisp
(progn
  (defun greet () (princ "hello"))
  (with-output-to-string (*standard-output*)
    (greet)
    (format t " ~a" 42))) ; => "hello 42"
```

A `nil` stream argument means the same thing as an omitted one -- it is the
`*standard-output*` designator, not "raw standard output". That is what makes
the common Common Lisp shape of a renderer forwarding its own optional
argument work under the redirect:

```lisp
(progn
  (defun emit (x &optional stream) (princ x stream))
  (with-output-to-string (*standard-output*)
    (emit "forwarded"))) ; => "forwarded"
```
