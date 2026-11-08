# Clojure: a macro whose expansion holds a class value is refused as unreadable

Difficulty: Medium

A macro may answer a form containing any object, and the oracle's compiler embeds what it can
(a `Class` constant is fine). Here the macro's answer goes back through the reader-level value
model, and a class value has no readable form:

- datascript 1.8.1 `defn+` (`datascript/db.cljc:73-81`, `patch-tag`): it puts the CLASS
  `java.lang.Boolean` (and `clojure.core$long`, j15) into the `:tag` metadata of the names it
  defines: `(assoc meta :tag java.lang.Boolean)`. Stops at `db.cljc:1089:1: macro 'defn+'
  answered an unreadable value: an unreadable value: #<java java.lang.Class>`.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defmacro m [v] `'~(mapv type v))
(prn (m [(n :g e) n 1]))
(defmacro tagged [] (list 'def (with-meta 'x {:tag java.lang.Boolean}) true))
(tagged)
(prn x (:tag (meta #'x)))
```

- oracle: `[clojure.lang.PersistentList clojure.lang.Symbol java.lang.Long]`, then
  `true java.lang.Boolean`
- interpreter and JVM class: `error: f.clj:2:6: macro 'm' answered an unreadable value: an
  unreadable symbol: :C%CLASS` (and the second macro: `an unreadable value: #<java
  java.lang.Class>`)

## Analysis

`ClojureMacroLowering` refuses a macro answer it cannot turn back into source data. Two kinds of
class value reach it: the front end's own class designators (`:C%CLASS` -- what `(type x)`
answers at macro time) and a host `java.lang.Class` (`java.lang.Boolean` evaluated in the
macro-time image). Both are constants; the lowering can embed them (a designator as the same
designator, a host class as a class reference the backend already knows how to emit -- `String`
in value position works today). In `:tag` metadata the class should behave like the symbol tag:
ignored by the backends, but visible in `(meta #'x)`.

Interacts with `.todo/h09` (what a class value is on the host backends): whatever h09 decides,
an expansion holding one must lower to the same value.

## Done when

- The repro prints the oracle's lines on the interpreter and the JVM, and on wasm prints the
  wasm class designators per the KB (ci-spec case).
- datascript's `defn+` lowers without the `patch-tag` hunk of j07's `datascript/patches.diff`.
