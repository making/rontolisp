# b91. Compact `.kb/clojure-frontend.md`

Difficulty: Medium

`.kb/clojure-frontend.md` is 1143 lines (2026-10-02) and keeps growing with every Clojure todo.

Rewrite it to what a future session needs before changing behavior:

- Delete history and narrative (how a decision was reached, which todo did what, superseded designs).
- Delete redundant text (the same point restated across sections).
- Delete what reading the code already tells (class/method walkthroughs, lists that mirror the source).
- Delete measurement logs. Keep a number only where a current design decision rests on it, as one line.
- Keep non-obvious invariants, deviations from the oracle and their reasons, and pointers to where things live.

Do this after the in-flight Clojure todos that edit the file have landed, so the rewrite does not conflict with them.
Check that other `.kb` files and `CLAUDE.md` links into this file still resolve after the rewrite.
