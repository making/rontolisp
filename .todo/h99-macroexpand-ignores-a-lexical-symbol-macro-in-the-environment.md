# h99. `macroexpand` with an `&environment` ignores a lexical `symbol-macrolet`

Difficulty: Medium

Measured 2026-11-04. A local `macrolet` is seen; a local symbol macro is not.

```lisp
(defmacro mx (f &environment env) (list 'quote (macroexpand f env)))
(macrolet ((lo (x) (list 'list x x))) (mx (lo 1)))  ; both (LIST 1 1)
(symbol-macrolet ((s 1)) (mx s))                    ; SBCL 1    rontolisp S
```

Consumer: 3bz keeps mutable state for its macros in a symbol macro (`deflate.lisp:67`):

```lisp
`(symbol-macrolet ((.current-state ,(list nil))) ...)
(setf (car (macroexpand '.current-state env)) s)
```

which stops with `RPLACA: The value 3BZ::.CURRENT-STATE is not of type CONS` at `deflate.lisp:92`.
opticl stops there through pngload. With pngload removed from the walked copy, opticl loads and
writes a PNG byte-identical to SBCL's.

The expansion has to be the SAME object the macro stored: the idiom mutates it. Mechanism to
reuse: `.kb/symbol-macrolet.md` (the shared shadow-aware substitution), which today substitutes
by walking, so an expander may not be holding the binding at all. Check all four backends: the
compile paths expand through `UserMacroExpander`.
