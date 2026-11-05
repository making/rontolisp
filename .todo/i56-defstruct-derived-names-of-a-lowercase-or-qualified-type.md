# i56. The names `defstruct` derives for a lowercase or qualified type

Difficulty: Low

Measured 2026-11-05 on all four backends against SBCL 2.2.9.

- `(defstruct |low| |x|)`: SBCL defines `MAKE-low` and `low-x`. Here `(fboundp '|MAKE-low|)` is NIL and a call of it is
  undefined: `LispMacroExpander.affixFor` keeps the affix lowercase for a base holding a lowercase letter, for the
  lowercase-authored internal sources. Tell those apart from a user's escaped name.
- `(defstruct other-pkg::q r)` under `cl-user`: SBCL interns `MAKE-Q` in the current package, so `cl-user::make-q` is
  the constructor and `other-pkg::make-q` is undefined. Here `other-pkg::make-q` is defined
  (`derivedSpellings`' `internPackage`).
