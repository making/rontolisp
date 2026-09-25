# The JVM's fetch reply differs from the interpreter's

Difficulty: Medium

The fetch corpus (`src/test/resources/fetch-spec.yaml`, `FetchSpecE2eTest`) skips three cases on
the JVM leg (checked 2026-09-25):

- every await converts the settled `HttpResponse` into a NEW plist with a new one-chunk body
  stream over the whole body (`JvmAsyncRuntimeBuilder.emitHttpResponseBranch`): `(eq (await f)
  (await f))` is NIL where the interpreter and a `--native` output answer T, and a body drained
  after the first await is whole again after the second;
- a repeated response field's values are joined into ONE pair, `("set-cookie" . "a=1, b=2")`;
  the interpreter keeps one pair per value;
- a URL `java.net.URI` refuses (`http://h/a b`) throws at the `fetch` CALL (`_fetch`'s
  `URI.create`), uncaught by a handler around the await; the interpreter fails the future
  (`HttpSupport.requestAsync`) and signals at the await.

Two `:headers` differences the corpus does not pin (it looks fields up by name): the JVM lists
them in REVERSE name order (the interpreter's are sorted, the JDK's `TreeMap`), and over HTTPS
both JDK backends negotiate HTTP/2 where the origin offers it, whose `:status` pseudo-field then
appears in `:headers` (seen on the interpreter against `https://example.com`).

## Goal

The JVM answers what the interpreter answers: remove the `jvm:` entries under `skip:`. Decide
whether `:headers` order is part of the contract (then pin it in the corpus) and whether
pseudo-fields belong in `:headers` at all; record the decisions in `.kb/fetch-http.md`.
