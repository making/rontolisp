# f65. Clojure: a dependency's Java class compiled against `clojure.lang` (reitit-core)

Difficulty: High

Found by the library probes (`.kb/clojure-frontend.md`, "Library probes", 2026-10-12):
`metosin/reitit-core` 0.11.0 lowers whole, but its default trie compiler
(`reitit.trie/compiler` -> `java-trie-compiler` under `:clj`) calls `reitit.Trie`, a Java class
in the same jar whose methods take and answer `clojure.lang.IPersistentMap` and
`clojure.lang.Keyword`. No `clojure.lang` class exists here, so:

- the interpreter dies with an uncaught `java.lang.NoClassDefFoundError:
  clojure/lang/IPersistentMap` (from `JavaInterop.resolve`'s reflection) -- a JVM crash, not a
  Clojure exception a program could catch;
- the JVM class needs the dependency jar on its run-time class path (`-o Probe.class` run as
  `java Probe` answers `reitit/Trie$Matcher` as an `ExceptionInfo` cause);
- wasm has no host at all (and `reitit.impl/parse` calls `.intern`, `.todo/f63`).

With the portable trie compiler, `(r/router routes {::trie/trie-compiler
(trie/clojure-trie-compiler)})`, the interpreter prints every probe line the oracle prints with
its default compiler (match-by-path, match-by-name, `match->path`, `routes`; the oracle itself
fails with that compiler on the JVM, a `^long`-hinted `IFn$OL` cast).

## What decides the design

- A class that cannot link must be a catchable refusal naming the missing `clojure.lang` class,
  on the interpreter and the JVM.
- Whether a library choosing `:clj` code that needs `clojure.lang` should get another branch: the
  reader takes `:clj` for every `.cljc` ("Reader conditionals"); a `clojure.lang` bridge for a
  library's Java classes would be a much larger surface. Measure how many Clojars jars ship such
  classes before deciding.
- The compiled JVM program's class path for a dependency jar (`.kb/java-interop.md`, "The
  program's Java class path").

## Plan

1. The crash as a refusal (a `ClojureInteropTest` row over a class whose linking fails).
2. The measurement and the decision above; re-probe reitit on four backends.
