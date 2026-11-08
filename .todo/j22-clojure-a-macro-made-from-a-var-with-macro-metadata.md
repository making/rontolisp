# Clojure: a var made a macro by `alter-meta! ... {:macro true}` is not a macro

Difficulty: Medium

specter 1.1.6 re-exports its macros from `com.rpl.specter.macros` under its main namespace
(`com/rpl/specter.cljc:57-65`):

```clojure
(defmacro defmacroalias [name target]
  `(do
     (def ~name (var ~target))
     (alter-meta! (var ~name) merge {:macro true})))
(defmacroalias richnav macros/richnav)
(defmacroalias nav macros/nav)
(defmacroalias defnav macros/defnav)
(defmacroalias defrichnav macros/defrichnav)
```

Here `defnav` stays a plain var, so `(defnav ^{:doc ...} STOP [] ...)` (`specter.cljc:666`) is a
call evaluating its arguments: `unknown name: STOP`. potemkin's `import-macro` and several
"re-export" helpers use the same var-metadata idiom.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defmacro dn [name & body] `(def ~name ~@body))
(defmacro defmacroalias [name target]
  `(do (def ~name (var ~target)) (alter-meta! (var ~name) merge {:macro true})))
(defmacroalias dn2 dn)
(dn2 X 5)
(prn X)
```

- oracle: `5` (the alias var's root is the `dn` VAR, which is invokable; the macro flag makes the
  compiler call it with `&form`/`&env` first)
- interpreter and JVM class: `error: f.clj:5:6: unknown name: X`
  (with `(def ~name ~(resolve target))` instead of `(var ~target)` too)

## Analysis

Macro-ness here is decided at lowering from `defmacro` forms (KB "Macros": "a macro var's root
its function of `(&form &env & args)`"). The oracle decides it per call from the var's `:macro`
metadata, which `alter-meta!`, `(.setMacro v)` and `(def ^:macro name f)` all set. To honor it,
the lowering must evaluate, at lowering time, the forms that change a var's macro flag (the
`alter-meta!` above runs at top level, after the `def`), and treat the var as a macro from the
next form on -- the macro-time image already evaluates `eval`/`resolve` while lowering, so it is
"run this top-level form in the image, then read the var's meta". The root here is a var
(`(var dn)`) -- calling it must call `dn`'s macro function with `&form`/`&env`.

Cover: `(def ^:macro m (fn [&form &env x] ...))`, `(alter-meta! #'f assoc :macro true)` on a
`defn`, `(.setMacro #'f)`, and the alias across namespaces (specter's shape: the alias is
defined in one namespace and used in another that `:refer`s it).

## Done when

- The repro and the variants above print the oracle's lines on all four backends (ci-spec).
- specter loads without the `defmacroalias` hunk of j07's `specter/patches.diff`.
