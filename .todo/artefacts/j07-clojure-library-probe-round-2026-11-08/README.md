# Clojure library probe round, 2026-11-08

One directory per library: `deps.edn` + `probe.clj` (the probe), `oracle.out` (`clj -M probe.clj`,
clj 1.12.6.1673), `verbatim.out`/`verbatim.err` (`java -jar target/rontolisp-*-exec.jar probe.clj`
on the interpreter, develop `a3c68a63f`), and `patches.diff` (the hand patches to the library's own
sources that took the probe to the oracle's output; each hunk is one gap, named in `.todo/j07`).

Re-run one probe verbatim:

```bash
cd <lib> && clj -M probe.clj > /tmp/oracle.out
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar probe.clj | diff /tmp/oracle.out -
```

Re-run past the blockers (the KB's "Library probes" harness): unzip the library jar into `src/`
(no `META-INF`), add `src/deps.edn` = `{:paths ["."]}`, apply `patches.diff` with
`patch -p1 -d src`, and point a `deps.edn` at it:
`{:deps {local/lib {:local/root "src"} <the library's own deps>}}`.
The library's own deps the patched copies need:

| library | extra deps beside `local/lib` |
|---|---|
| datascript 1.8.1 | `persistent-sorted-set/persistent-sorted-set {:mvn/version "0.3.1"}`, `io.github.tonsky/extend-clj {:mvn/version "0.1.0"}` |
| rewrite-clj 1.3.59 | `org.clojure/tools.reader {:mvn/version "1.6.0"}` |
| specter 1.1.6 | `riddley/riddley {:mvn/version "0.1.12"}`, and the jar's two classes (`com/rpl/specter/{Util,MutableCell}.class`) repacked as a jar under a second `:local/root` |

editscript's `patches.diff` is the WASM-only patch (`System/nanoTime`); the interpreter, the JVM
class need none.
