# m78. Java library: `java.net.CookieManager` for an `HttpClient`

Difficulty: Medium

`servlet-httpserver-keyvalue`'s `KeyValueSessionStoreTest` stops at
`HttpClient.newBuilder().cookieHandler(new CookieManager())`: `java.net.CookieManager` and
`CookieHandler` are not in the library (`.kb/java-frontend.md`, "The tree's suites that
run", with the source path that reaches the module). Measured 2026-11-26: past them, and past
AssertJ's `usingRecursiveComparison`/`asInstanceOf` (`.todo/m62`), both tests run on the
interpreter.

Written from the Javadoc and RFC 6265 with probes of a JDK: the default policy
(`ACCEPT_ORIGINAL_SERVER`), the in-memory store, and `HttpClient` asking the handler for the
request's `Cookie` header and handing it each response's `Set-Cookie`. Then the suite joins
`JavaSpecE2eTest#theSuitesOfTheTreesModulesRunUnchanged` once m62 is done (on Preview 1 the
`@WitImport` store has no host: that leg is measured there).
