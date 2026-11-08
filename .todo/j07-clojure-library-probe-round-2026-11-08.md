# Clojure: library probe round 2026-11-08 -- ten unprobed libraries, eight stop

Difficulty: Medium

The round itself, and the index of what it found. Each gap is its own item (j08-j28); this item
closes when the libraries below are re-probed after those land and the KB table says what each
backend prints.

## What was probed

Ten widely used libraries that no earlier round touched (the KB's "Library probes" table has
medley ... tools.reader; malli, instaparse and core.async have their own sections), each the
newest release on Clojars/Central on 2026-11-08. Harness: the KB's (`.kb/clojure-frontend.md`,
"Library probes"): a scratch directory per library, `{:deps {lib {:mvn/version "v"}}}` plus a
7-12 line `probe.clj`, diffed against `clj -M probe.clj` (clj 1.12.6.1673). Interpreter on
develop `a3c68a63f`; then each library's sources copied under a `:local/root` and patched by
hand past every blocker until the probe printed the oracle's lines -- so the list below is
everything each probe path needs, not just the first stop. Probes, oracle outputs, the verbatim
outputs and the patches: `.todo/artefacts/j07-clojure-library-probe-round-2026-11-08/`
(`README.md` there says how to re-run).

| library | verbatim (interpreter) | first stop | patched to `=` by |
|---|---|---|---|
| com.stuartsierra/component 1.2.0 | `=` (also JVM, P1, component; 2 `JAVA:CALL` warnings off the path on wasm) | | |
| juji/editscript 0.8.0 | `=` (also JVM); P1 and component stop | `System/nanoTime`, then `java.util.IdentityHashMap` (`JAVA:STATIC`/`JAVA:NEW`) | j28 |
| org.clojure/data.csv 1.1.1 | 5 of 6 lines | `(io/reader (java.io.StringReader. ...))` | j16 |
| aero/aero 1.1.6 | stops at load | `clojure.lang.LineNumberingPushbackReader.` | j15, j12, j16 |
| org.clojure/core.match 1.1.1 | stops at load | multi-arity `defmethod` (`match.clj:208`) | j08, j09, j10, j11, h09 |
| org.clojure/core.logic 1.1.1 | stops at load | `instance?` of a `definterface` by its qualified name (`protocols.clj:194`) | j19, j10, j12, h09; then `()` vs `nil` (pinned deviation) |
| prismatic/schema 1.4.2 | stops at load | `clojure.lang.Compiler/CHAR_MAP` in a macro (`utils.cljc:58`) | j15, j24, j19, j14, j23, j13, h09 |
| com.rpl/specter 1.1.6 | stops at load | riddley's `proxy` of `Compiler$ObjMethod` (`riddley/compiler.clj:12`) | j27, j15, j13, j23, j22, j21, j12, j25, j17 |
| rewrite-clj/rewrite-clj 1.3.59 | stops at load | `defmethod print-method` of a fn parameter (`node/protocols.cljc:166`) | j14, j13, j18 |
| datascript/datascript 1.8.1 | stops at load | `clojure.core$long` (`db.cljc:80`) | not reached: j26 (persistent-sorted-set) |

Library-side facts worth keeping:

- specter, schema and editscript ship a babashka path (`#?(:bb ...)` or
  `(System/getProperty "babashka.version")`) that avoids exactly the JVM internals they stop on
  here (riddley, `__methodImplCache`, the inline path cache). rontolisp does not claim `:bb`
  (wrong promise: `:bb` code also calls `sci.lang.Type` and babashka-only namespaces); the
  items below make the `:clj` paths work instead.
- specter's jar carries two plain Java classes (`com.rpl.specter.Util`, `MutableCell`);
  with them on a jar dependency the interpreter loads them. In a `:local/root` DIRECTORY holding
  the `.class` files they are not found (`No such class: com.rpl.specter.MutableCell`); not filed
  (no library ships that way), noted here only.

## Done when

Every library in the table is re-probed verbatim (no patches) on the interpreter, the JVM class,
wasm P1 and the component after j08-j28 land, the rows join the KB's "Library probes" table with
what each backend prints and what still stops it, and any new stop is filed (a large one) or fixed
in the same session (a small one). datascript and specter may stay red behind their decision items
(j26, j27); their rows say so.
