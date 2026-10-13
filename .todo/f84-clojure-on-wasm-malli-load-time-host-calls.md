# f84. Clojure on wasm: malli's load-time host calls (`System/getProperty`, `HashMap`)

Difficulty: Medium

Found closing `.todo/f66` (2026-10-13): malli 0.20.3 loads and its probe is the
oracle's line for line on the interpreter and the JVM. Past `.todo/f83` (the
wide-lambda build stop), loading on wasm would reach the host calls the load
evaluates: `System/getProperty` (`malli.registry/mode`, `malli.registry/type`;
`dynaload`'s `if-bb` reads `babashka.version` at macro time, which is fine --
macro time runs on the host), and `java.util.HashMap` (`fast-registry`'s
`(doto (HashMap. 1024 0.25) (.putAll m))`, built at load for the default
registry). On wasm a `java:` call is a call-time error, so the load would fail.

## What decides the design

- `System/getProperty` of a literal key could lower to a fixed answer per key
  (nil here, like a host without the property -- the oracle's answer where no
  property is set), or stay host-only with the load restructured; `HashMap`
  needs a portable stand-in or a lowering of the registry to the table the maps
  already are. `java.util.regex.Pattern` beside them already lowers portably
  ("Regex" rows) and needs nothing.
- Out of scope: the wide-lambda build stop (`.todo/f83`).

## Plan

1. The f66 probe directory builds to wasm and prints the oracle's lines under a
   wasm runner.
2. clojure-spec rows where the behavior is observable, four backends.
