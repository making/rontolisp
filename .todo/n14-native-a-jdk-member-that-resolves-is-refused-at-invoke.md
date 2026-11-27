# Native binary: a JDK member the front end resolves is refused when it runs

Difficulty: Low

The front end's member tables on a native image hold methods the image's build
did not register, so a call resolves and then dies on the invocation: the
refusal is `org.graalvm.nativeimage.MissingReflectionRegistrationError`, an
`Error` no `catch` or `handler-case` takes, so the program stops rather than
reporting the method it could not call:

```clojure
(println (try (java.lang.Integer/parseInt "x") (catch Exception e (ex-message e))))
```

```
Cannot reflectively invoke method 'public java.lang.String
java.lang.Throwable.getMessage()'
```

Measured 2026-11-27 (GraalVM 25.0.3, macOS aarch64), the JVM's interpreter
printing the message. The same shape, all resolved and all refused to run:
`java.lang.Throwable.getMessage`/`toString` (any host exception a program
prints or asks a message of, so a caught failure is unusable), and
`java.lang.Class.isEnum`, `isInterface`, `isAnnotation`, `getComponentType`
(class readers; `cast` runs, `asSubclass` is not even resolved). The walk's
members are registered already (`ClojureInteropLowering.HOST_STATIC_CLASSES`,
`.kb/clojure-frontend.md`, "Host classes"); these are outside that list.

Register them (`reachability-metadata.json`: `java.lang.Throwable`'s message
readers, and `java.lang.Class`'s readers and `asSubclass`), with
`ClojureHostImageTypesTest` extended to the members it does not model today,
and `ClojureValueMethodLowering`'s class kind reading a designator receiver
through the same list.

Done when each form above answers on the binary what the JVM's interpreter
answers, pinned by a run leg comparing the two
(`e2e/ClojureNativeLoweringE2eTest`), and when a thrown-and-caught host
exception prints its message there.
