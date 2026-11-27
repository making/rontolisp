# Native binary: a class designator passed where Java takes a Class reaches it as the handle

Difficulty: Medium

On the native binary's interpreter a class value the host makes none of is a designator
(`%clojure-host-classes-p` false; `.kb/clojure-frontend.md`, "Class values"), and a
designator crosses to a `java:` call as one `java:handle` per class name
(`%clojure-designator-host`), declared `java.lang.Class` so a host map keys it like the
oracle's one Class object. A *method parameter* of that class does not take the handle: the
handle object itself reaches the invoke, which fails before the call runs:

```clojure
(println (.isAssignableFrom Number (class 1)))
```

```
Unhandled condition: error calling java.lang.Class.isAssignableFrom:
java.lang.ClassCastException: class am.ik.rontolisp.runtime.RontoJavaHandle cannot be cast
to class java.lang.Class
```

The JVM's interpreter prints `true`: there `(class 1)` is a real `java.lang.Class`. Measured
2026-11-27 (GraalVM 25.0.3, macOS aarch64). The receiver side is fine -- `(.isInstance
CharSequence "a")` answers `true` there -- and `isa?`, `parents` and `ancestors` never cross
(`.kb/clojure-frontend.md`, "Host classes"), so this is the argument path alone. No
`clojure-spec.yaml` leg reaches the binary, so nothing pins it.

Two shapes were considered and neither is measured: the handle carries the class object
(`Class/forName` of its name, which the binary finds) as its wrapped value, so the
marshaller's existing conversion applies; or the interpreter's argument marshalling reads a
designator (or a handle declared `java.lang.Class`) as such a class object at the call, the
way `%clojure-host-value` converts a designator for a receiver.

Done when the program above prints `true` on the native binary, like the JVM's interpreter,
and a designator passed to a method, a constructor and a static taking `java.lang.Class`
runs there; pinned by a run leg where the JVM's interpreter and the binary are compared
(`e2e/ClojureNativeLoweringE2eTest`, as its host class walk is).
