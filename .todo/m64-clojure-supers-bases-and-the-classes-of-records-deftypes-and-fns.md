# Clojure: supers and bases of a class value, and the classes of records, deftypes and functions

Difficulty: Medium

A core value's class is a `java.lang.Class` on the interpreter and in a JVM program reaching the
host (a `clojure.lang` class a stub class), the `(:C%CLASS name)` designator elsewhere
(`.kb/clojure-frontend.md`, "Class values"). Left of the oracle's answers:

- `supers` and `bases` refuse every class ("supers is not supported yet: host classes are not
  answered on every backend"); the oracle answers sets of classes (`(bases Long)`, `(supers
  (class []))`). Where a host is, the class's own reflection answers (a stub carries the oracle's
  supertypes); elsewhere the stub rows (`%clojure-lang-classes`) and `ClojureClassBases`' rows,
  each as its class value.
- A record's or deftype's class is its designator on every backend, so Java takes a handle where
  a `Class` is expected (`(.isAssignableFrom clojure.lang.IRecord (class r))` is a cast failure)
  and `supers` of it stays refused. A stub per type (`IRecord`/`IType`, the protocol interfaces
  its body names) would replace the tag at `ClojureProtocolLowering.typeTagForm`.
- Every function's class is `clojure.lang.AFunction`, where the oracle compiles a class per
  function (`user$f`); `.getClass` of a function answers the designator of that name
  (`ClojureValueMethodLowering.FN_CLASS_NAME`), so `(= (class f) (.getClass f))` is false.
- A stub declares only the statics this front end answers, so `.getMethods` and `clojure.reflect`
  of a `clojure.lang` class answer those alone.

Done when `(bases Long)` and `(supers (class []))` answer the oracle's on all four backends and
`(= (class f) (.getClass f))` holds.
