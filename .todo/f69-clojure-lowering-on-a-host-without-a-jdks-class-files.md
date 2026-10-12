# f69. Clojure lowering on a host without a JDK's class files (the binary with no JDK, the browser)

Difficulty: Medium

The native binary lowers a Clojure host form against the JDK's class files it finds
(`.kb/clojure-frontend.md`, "Host classes"). With no JDK found (no `lib/ct.sym` via `java.home`,
`JAVA_HOME` or the `java` on `PATH`) it answers from its own image's reflection, as every build
did before 2026-10-12, and so does the browser playground (the web image reads the same
`META-INF/native-image`). That reflection holds only what the build registered. Measured
2026-10-12 with the binary of that morning, which always answered so:

- `(Exception. "outer" (ex-info "inner" {}))`, `(IllegalStateException. "x")`,
  `(RuntimeException.)` lower to `java:new` (`JAVA:NEW is undefined` on wasm): the image holds
  `Exception`'s `(String)` constructor alone and no `IllegalStateException` constructor.
- `(Thread/currentThread)` lowers to `java:field` (no `Thread` method is registered).
- A `Comparable` face has no `compareTo`; a `--java-classpath` class is unseen.
- `(catch java.util.concurrent.TimeoutException e ...)` is `Unable to resolve classname`, as
  are `UncheckedIOException`, `URISyntaxException`, `PatternSyntaxException`,
  `CancellationException`, `NoSuchFileException`: `Class.forName` finds only classes in the
  image.

The decisions every target takes, wasm included, read JDK classes the front end names in its
own code: the `ClojureThrowables.PARENTS` throwables (their constructors and methods),
`ClojureInteropLowering.HOSTLESS_RECEIVERS`, `ClojureValueMethodLowering.HOST_KINDS`,
`ClojureTimeValueLowering.KINDS`, `ClojureIoLowering`'s API types, `Object`, `Throwable`.
Registering each as a type in `reachability-metadata.json` (`{"type": "java.lang.IllegalStateException"}`)
makes the image answer every member query of it as a JVM does (probe image, GraalVM 25.0.4,
2026-10-12: a registered `Thread` answered its 53 methods and 9 constructors, a registered
`TimeoutException` resolved and answered in full). A test would hold the registered list to
those constants. A class a program names outside them stays partial without a JDK; a warning
when the binary finds none would say so (`JvmLispCompiler`'s `no JDK found` warning for `java:`
sites is the model). Weigh the web image's size, which the playground downloads.

Check: `ClojureNativeLoweringE2eTest` with the binary seeing no JDK (`JAVA_HOME` unset, no
`java` on `PATH`) over the programs whose host forms name only those classes.
