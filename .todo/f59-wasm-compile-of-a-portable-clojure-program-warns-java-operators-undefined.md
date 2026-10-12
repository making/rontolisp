# f59. A wasm compile of a portable Clojure program warns `JAVA:CALL is undefined`

Difficulty: Medium

A Clojure program that runs identically on all four backends still makes a wasm compile (Preview 1
and `--component`) print warnings naming `java:` operators, because a portable value's lowering
keeps a host arm (measured 2026-10-12 on the exec jar):

| program | warnings on `-o x.wasm` / `--component` |
|---|---|
| `(println (count (.getBytes "abc" "UTF-8")))` | `the function JAVA:CALL is undefined; compiled as a call-time error` |
| `(String. (.getBytes "abc" "UTF-8") "UTF-8")` | `JAVA:CALL`, `JAVA:NEW` |
| `(.getBytes "é" java.nio.charset.StandardCharsets/UTF_8)` | `JAVA:CALL`, `JAVA:STATIC`, `JAVA:NEW` |
| a `deftype` overriding `equals`/`hashCode`, reading `(.-s ^K o)` | `JAVA:CALL`, `JAVA:FIELD` |
| `examples/clojure/collections.clj`, `bundled-namespaces.clj`, `deps-project` | up to six: `CALL`, `NEW`, `HANDLE`, `VIEW`, `FIELD`, `STATIC`, `SUBCLASS` |

Each program prints the oracle's output on wasm; `--warnings-as-errors` does not count them (exit
0), but a reader takes them for a program that reaches Java and will fail. The arm a wasm target
can never take (the host branch of the byte-array, charset and `Object`-method dispatch,
`.kb/clojure-frontend.md` "Byte arrays", "Charset values", "Java faces") is what names them.

## What decides the design

- Whether the strip that drops arm families a program never takes
  (`.kb/clojure-frontend.md` "Sorted collections", the interpreted whole program's strip) can
  drop the host arms on a target without a host, so the forms naming `java:` operators never
  reach the wasm backend -- versus muting the warning for library-spliced forms, which would
  also hide a real call a program makes.

## Plan

1. A `CompileWarnings` assertion over the table's programs on both wasm targets (no `JAVA:`
   warning), red first.
2. Strip the host arms where the target has no host; keep a program's own `java:` call warning.
3. `.kb/clojure-frontend.md`: where the strip happens.
