# uiop:with-deprecation

`(uiop:with-deprecation (level) definitions...)`

Establishes the definitions it wraps, exactly as written, and returns the last
one's value. It also marks each wrapped `defun` as deprecated: the first call
evaluates the `level` form once and signals the deprecation condition the level
selects.

The `level` form is typically `(uiop:version-deprecation ...)`, which maps a
version string to `:style-warning` / `:warning` / `:error` / `:delete`. Each
level signals its own condition class — `deprecated-function-style-warning`,
`deprecated-function-warning`, `deprecated-function-error`, or
`deprecated-function-should-be-deleted` — once per function (upstream evaluates
the level at macro-expansion time; this port has no expansion-time evaluator, so
it is evaluated on the first call instead). Forms other than `defun` pass through
untouched.

The expansion splices at top level, so wrapped top-level `defun`s stay top-level
definitions on the compile backends (that is the shape libraries use, usually
inside an `eval-when`).

```lisp
(uiop:with-deprecation ((uiop:version-deprecation "1.1" :delete "1.1"))
  (defun old-gone () 1))
(handler-case (old-gone)
  (uiop:deprecated-function-should-be-deleted (c)
    (list :caught (uiop:deprecated-function-name c))))   ; => (:CAUGHT OLD-GONE)
```

`uiop` is ASDF's portability layer, not part of Common Lisp: the name is only
reachable with the `uiop:` qualifier.

## Backend support

Works on all four backends: it is a built-in macro expansion shared by the
interpreter and both compilers. Like the other built-in macros it has no
function value (`#'uiop:with-deprecation` is an error).
