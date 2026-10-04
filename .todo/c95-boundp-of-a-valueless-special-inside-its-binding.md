# c95. boundp of a special with no global value is nil inside a binding of it

Difficulty: High

```lisp
(defvar *bv*)
(defun bv-set () (setq *bv* 2))
(print (list (boundp '*bv*) (let ((*bv* 1)) (boundp '*bv*)) (boundp '*bv*)))
(print (progn (let ((*bv* 1)) (bv-set)) (boundp '*bv*)))
;; interpreter / SBCL: (NIL T NIL) then NIL
;; JVM, wasm Preview 1, component: (NIL NIL NIL) then T
```

On the compile paths `boundp` answers from the eval mirror's entry (`_genv` / `GLOBAL_ENV`), a
witness that "a global store happened": a `let` never writes it, and a callee's `setq` inside the
binding writes it for good. Neither variable representation has an unbound state (`nil` is Java
`null` / a null ref), so the variable cannot answer. `symbol-value` and `eval` of such a special
read `nil` instead of signalling (documented in `doc/*/reference/functions/symbol-value.md`).
Since `.todo/c89` VALUES by name come from the variable (`.kb/dynamic-special-variables.md`,
"Compile-path limitations" 2 and "One home"); this is the bound-ness half that item left.

The idiom it breaks: `(defvar *request*)` bound per request and probed with
`(boundp '*request*)` by code that may run outside one.

Candidate designs, none measured yet:
- An UNBOUND marker as the initial value of a special with no value, read through a check only
  in programs that probe bound-ness (`boundp` / `symbol-value` / eval) -- every other program
  must stay byte-identical, and a direct read must keep answering nil there.
- Per-special binding depth maintained by the `let` / parameter / `progv` binders, again only in
  programs that probe -- the JVM could read its `_d$` cell instead, wasm's shallow binding cannot.
- Write the mirror witness only when no binding is active (the JVM can tell from `_dset`; wasm
  cannot), which fixes the second line only.
