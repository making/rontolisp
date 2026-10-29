# Clojure: core.async loads and runs through deps.edn

Difficulty: High

`{:deps {org.clojure/core.async {:mvn/version "1.6.681"}}}` with
`(let [c (a/chan)] (a/go (a/>! c 1)) (println (a/<!! c)))` stops at load time on the
marker protocol (`.todo/g86`). Neither cljw (blocked on `Long/getLong`) nor rontolisp runs
it today, so this would be an edge over cljw.

After `g86`, probe the next blockers with the "Library probes" harness
(`.kb/clojure-frontend.md`): the `go` macro's `ioc` state machine leans on `tools.analyzer`
(itself a library probe), and the channels on `java.util.concurrent` locks and executors.
Scope: interpreter and JVM first; on WASM, `go` blocks over a single-threaded scheduler is
a separate decision. Record each blocker as infrastructure, never special-cased.
