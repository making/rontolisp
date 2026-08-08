# uiop:with-deprecation

`(uiop:with-deprecation (level) definitions...)`

Establishes the definitions it wraps, exactly as written, and returns the last
one's value. Real UIOP additionally marks them as deprecated so that a later
caller gets a warning at `level`.

**rontolisp drops that diagnostic.** There is no deprecation-warning machinery
and no compile-time warning channel to route one through, so the honest lowering
is `(progn definitions...)` — the level form is evaluated by nothing and ignored.
A library that wraps part of its API in this macro therefore loads and runs
normally; you simply never hear that a name is on its way out.

The expansion splices at top level, so wrapped top-level `defun`s stay top-level
definitions on the compile backends (that is the shape libraries use, usually
inside an `eval-when`).

```lisp
(uiop:with-deprecation (:style-warning)
  (defun old-double (x) (* x 2))
  (defun old-triple (x) (* x 3)))
(list (old-double 4) (old-triple 4))   ; => (8 12)
```

`uiop` is ASDF's portability layer, not part of Common Lisp: the name is only
reachable with the `uiop:` qualifier.

## Backend support

Works on all four backends: it is a built-in macro expansion shared by the
interpreter and both compilers. Like the other built-in macros it has no
function value (`#'uiop:with-deprecation` is an error).
