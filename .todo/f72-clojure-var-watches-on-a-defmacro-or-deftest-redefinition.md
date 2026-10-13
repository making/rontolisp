# f72. Clojure: a var's watches and validator on a `defmacro` or `deftest` redefinition

Difficulty: Small

Left by f61 (2026-10-13): a `def`, `defn`, `defonce` or `with-redefs` replacing a
var's root validates and notifies like the oracle's `bindRoot`, but a `defmacro`
redefinition still replaces its root silently, and a `deftest` redefinition too:

| program | clj 1.12.6 | here |
|---|---|---|
| `(defmacro mm [] :m1) (add-watch #'mm :k f) (defmacro mm [] :m2)` | `f` called | not called |
| `(set-validator! #'mm (fn [v] false)) (defmacro mm [] :m3)` | `Invalid reference state` | stored |

A `defn` with no value cell (a plain redefinition, or a first one over a declared or
valued root) validates only once its definition is bound: where the validator refuses
it, the call reports the oracle's `Invalid reference state` but later calls reach the
refused definition (the oracle keeps the old one). Both are document rows in
`doc/*/clojure/deviations.md`.

## What decides the design

- A macro has no function value (deref of its var signals) and no value cell, so its
  site needs the same statement shape as a cell-less `defn`: the
  `%clojure-var-defn-notify` statement arm behind the expander registration, dropped
  by the strip where the program names no watch verb.
- A `deftest` lowers to zero-argument function definitions; the same arm fits behind
  them, with the previous test function as the replaced root.
- The refused cell-less `defn` would need its calls routed through the var to keep
  the old definition -- the direct-linking opt-out `^:redef` already buys that per
  var, so this stays a documented deviation unless routing changes.

## Plan

1. clojure-spec rows for the table, oracle-identical, four backends.
2. The statement arms for a redefined `defmacro` and `deftest`; the
   `ClojureLibraryTest` pin stays green (no-watch programs lower unchanged).
3. Shrink the deviation rows in `doc/en` + `doc/ja` and the `.kb` bullet.
