# Clojure: tools.reader 1.3.6 reads on both wasm backends

Difficulty: Medium

tools.reader 1.3.6 loads verbatim and its probe prints the oracle's lines on the interpreter
and the JVM (`.kb/clojure-frontend.md`, "Library probes"). On wasm P1 and the component the
namespace loads, but every `read`/`read-string` stops at `(LinkedList.)`: `read` makes a
`java.util.LinkedList` as its pending-forms queue (`reader.clj` lines 988 and 993), a
`JAVA:NEW` call-time error where no host is. Measured 2026-10-29.

Plan: a portable `java.util.LinkedList` where the host is not, like the `StringBuilder` and
`java.util.HashMap` stand-ins ("HashMap construction"), answering the members tools.reader
calls on the queue (read them off `reader.clj`'s pending-forms uses: `isEmpty`, `remove`,
`addAll` / `add` at an index ...). Then re-run the probe on both wasm backends and record
what stops it next.

Done when the "Library probes" row's P1 and component columns read `=` for the 12-line probe.
