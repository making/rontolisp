# i41. `check-type` of a class or a deftype passes anything

Difficulty: Medium

`LispMacroExpander.expandCheckType` falls back to `t` when `makeTypeTest` does not know the specifier,
which is any class, condition, deftype, and a compound holding one. Measured 2026-11-05 on all four:
`(let ((a "x")) (check-type a foo))` with `(defclass foo () ())` passes, as does
`(check-type a (or null foo))`. SBCL signals a `type-error`.

Falling back to a run-time `typep` was tried and is not enough on its own, because it turns what `typep`
misanswers into refusals of correct programs. Against SBCL 2.2.9 on the interpreter these answered wrong
under that fallback:

- `(check-type a (mod 5))` with 1, `random-state`, `compiled-function` on `#'car`.
- `simple-condition` on a `(make-condition 'simple-error)`.

So fix `typep` for those first, or guard the fallback. The expansion runs inside the compilers, after the
whole-program gates (`.kb/declarations-type-checks.md`), so a `typep` or `find-class` it adds has to be
visible to those gates, as `check-type` already is by name. `asdf-search.lisp`'s `%asdf-check-found`
spells its test out for this reason and can become a `check-type` afterwards.

`random-state-p` is also undefined on all four.
