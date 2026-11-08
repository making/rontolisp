# Clojure: a `def` a macro produces cannot call itself, and a `declare` inside a top-level `do` stays unbound

Difficulty: Medium

Two shapes the j07 probe round met three times. They may share one cause (the pre-scan registers
the names of top-level definitions in the source, not of definitions an expansion or a `do`
member produces), so they are one item; split it if the causes differ.

## 1. A macro-generated `(def name (fn ... (name ...)))`

- core.logic 1.1.1 `defne` -> `defnm` (`clojure/core/logic.clj:1647`):
  `` `(def ~(vary-meta n ...) ~e) `` where `e` is `(fn [x l] ... (membero x tail))` --
  `logic.clj:1763: unknown name: membero`.
- specter 1.1.6 `defdynamicnav` (`com/rpl/specter.cljc:187`): `` `(def ~name (dynamicnav ~@args)) ``
  whose body calls `if-path` -- `specter.cljc:1383: unknown name: if-path`.

Repro (2026-11-08, develop `a3c68a63f`):

```clojure
(defmacro defrec [n] `(def ~n (fn [x#] (if (zero? x#) :done (~n (dec x#))))))
(defrec a)
(prn (a 3))
```

- oracle: `:done`
- interpreter and JVM class: `error: f.clj:2:1: unknown name: a`

Without the macro, `(def f (fn [n] (if (zero? n) 0 (f (dec n)))))` works, and so does the
macro when its expansion is `(do (declare ~n) (def ~n ...))` -- the patch both libraries got.
In the oracle `def` interns the var before compiling the init, so the init always sees its own
name.

## 2. `declare`, a caller, then the `defn`, all inside one top-level `do`

aero 1.1.6 wraps its whole implementation in `(macro/usetime ...)`
(`aero/core.cljc:27-431`), a macro answering `(do ~@body)`; the body starts with
`(declare read-config)`, callers use it, and `(defn read-config ...)` comes last. Every
`aero/read-config` call: `Attempting to call unbound fn: #'aero.core/read-config`.

Repro:

```clojure
(do (declare rc)
    (defn uses [] (rc 1))
    (defn rc [s] [s]))
(prn (uses))
```

- oracle: `[1]`
- interpreter and JVM class: `Unhandled condition: Attempting to call unbound fn: #'user/rc`

The same three forms at top level without the `do` print `[1]`; `(do (declare rc) (defn rc ...))`
with no caller in between also works -- only a caller lowered between the `declare` and the
`defn` keeps the unbound var. (KB: "`declare`: a pre-scan forward declaration
(`Kind.DECLARED`); a real definition wins" -- the win does not reach a caller inside the same
`do`.)

## Done when

- Both repros print the oracle's lines on all four backends (ci-spec cases), plus: the macro
  variant whose name carries metadata (`(vary-meta n assoc :arglists ...)`, core.logic's shape),
  and the `do` produced by a macro (aero's `usetime`).
- core.logic, specter and aero load without the `declare`/unwrap hunks of j07's
  `patches.diff`.
- `.kb/clojure-frontend.md` says what the pre-scan sees inside `do` and inside expansions.
