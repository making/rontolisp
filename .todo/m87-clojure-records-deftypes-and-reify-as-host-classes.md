# Clojure: records, deftypes and reify as host classes

Difficulty: Medium

A core value's class, a fn's included, is a `java.lang.Class` where the program reaches the host
(`.kb/clojure-frontend.md`, "Class values"); left as designators on every backend:

- A record's or deftype's class is its `(:C%CLASS "ns.Name")` tag, so Java takes a handle where a
  `Class` is expected: `(.isAssignableFrom clojure.lang.IRecord (class r))` is a cast failure (the
  oracle's `true`). `supers`/`bases` answer it from the bases its definition notes
  (`%clojure-note-type-bases`). A stub per type -- its oracle supertypes, the protocol interfaces
  its body names as stubs of their own -- would replace the tag at
  `ClojureProtocolLowering.typeTagForm`; every row keyed by the tag (protocol, inline, interface,
  multimethod, hierarchy, `print-method`, registry) would need the class object as its key, or a
  map from it.
- A `reify`'s class is the designator `reify` (the oracle's `user$eval1$reify__2`, its bases
  `Object`, the body's interfaces, `IObj`); `supers`/`bases` of it are refused.
- A `definterface` name evaluates to its name string (the oracle's a `Class`), so `class?` of it is
  false and `bases` of it is the oracle's `ClassCastException`.
- A stub declares only the statics this front end answers, so `.getMethods` and `clojure.reflect`
  of a `clojure.lang` class answer those alone.

Done when `(.isAssignableFrom clojure.lang.IRecord (class r))`, `(bases (class (reify)))` and
`(class? I)` of a `definterface` answer the oracle's on the backends where the host is, and the
designator backends stay as they are.
