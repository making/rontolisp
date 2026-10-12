# f61. Clojure: a var's watches and validator on a `def`, `defn` or `with-redefs` replacing its root

Difficulty: Medium

Left by f56 (2026-10-12): a var's watches and validator run on `alter-var-root` only
(`.kb/clojure-frontend.md`, "State", validators and watches; `doc/*/clojure/deviations.md`).

| program | clj 1.12.6 | here |
|---|---|---|
| `(def x 1) (add-watch #'x :k f) (def x 10)` | `f` called `1 -> 10` | not called |
| `(with-redefs [x 20] ...)` on a watched `x` | called `10 -> 20`, then `20 -> 10` | not called |
| `(set-validator! #'x pos?) (def x -1)` | `Invalid reference state` | stored |

The oracle's `bindRoot` (every `def`, `defn`, `with-redefs-fn`) validates and notifies.

## What decides the design

- A program that names no watch verb must lower as before: the store has to be a
  `ClojureArms.Family.WATCH` arm. `with-redefs` already stores temporaries, so a test arm
  `(if (%clojure-var-watched-p "ns/x") (rebind ...) (setq cell v))` folds back exactly.
- A `def`'s `(setq cell INIT)` has no temporary: a test arm duplicates INIT. A new fold
  shape (a store over a getter and a setter closure folding to its `setq`) avoids that.
- A redefined `defn` stores nothing at run time (each definition is its own `%defN`
  function); its site needs a statement arm the strip can drop at top level, and the
  `^:dynamic` `defparameter` and a session's redefinition are their own shapes.

## Plan

1. clojure-spec rows for the table, oracle-identical, four backends.
2. The arms for `with-redefs`, `def` (plain and dynamic) and a redefined `defn`; a
   `ClojureLibraryTest` pin that a program without a watch verb lowers unchanged.
3. Drop the deviation from `doc/en` + `doc/ja` and the `.kb` bullet.
