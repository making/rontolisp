# Native binary: no URL protocol is enabled

Difficulty: Low

A `java.net.URL` over `http`, `https` or `jar` is refused on the native binary,
where the JVM's interpreter builds it:

```clojure
(println (try (java.net.URL. "https://a/") (catch Exception e (ex-message e))))
```

```
Accessing a URL protocol that was not enabled. The URL protocol https is
supported but not enabled by default. It must be enabled by adding
--enable-url-protocols=https to the native-image command line
```

Measured 2026-11-28 (GraalVM 25.0.3, macOS aarch64); `file:` works. The image
build (`pom.xml`, native profile) passes no `--enable-url-protocols`, so only
the protocol every image holds answers. The URL class itself is registered
(`reachability-metadata.json`, `ClojureInteropLowering.HOST_CALLABLE_CLASSES`),
so the refusal is the handler the build leaves out, not reflection.

Add the protocols the front end serves URLs for (`http`, `https`, and `jar`
where a program opens one) to the native profile's build args, weighed for the
image size they pull in, and pin a construction and a read of a URL of such a
protocol against the JVM interpreter's answer in
`e2e/ClojureNativeLoweringE2eTest`.
