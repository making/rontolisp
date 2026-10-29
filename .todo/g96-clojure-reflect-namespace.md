# Clojure: ship `clojure.reflect`

Difficulty: Medium

`(require '[clojure.reflect :as reflect])` is `unknown namespace: clojure.reflect`: it is a
clojure.jar namespace the front end does not ship. tools.analyzer.jvm's `jvm.utils` requires
it and calls `(reflect/type-reflect Object :reflector (reflect/->JavaReflector loader))` at
load, so this is the first blocker of the `go` chain (`.kb/clojure-frontend.md`, "core.async
and tools.analyzer.jvm").

Write it for this front end from the documented behaviour, like the other clojure.jar
namespaces ("clojure.jar namespaces"): `type-reflect`, `reflect`, `Reflector`,
`->JavaReflector`, the `Constructor`/`Method`/`Field` records (`:name` a symbol,
`:parameter-types`/`:return-type`/`:declaring-class` symbols, `:flags` keywords), `typename`,
`:ancestors true`. Host reflection where the host is (interpreter, JVM); on WASM a call-time
refusal. Diff a dozen calls against clj 1.12.6 (`(reflect/reflect "")`, members of `Object`,
`java.util.List`, a primitive array).
