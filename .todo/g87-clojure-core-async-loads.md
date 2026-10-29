# Clojure: core.async loads and runs through deps.edn

Difficulty: High

`{:deps {org.clojure/core.async {:mvn/version "1.6.681"}}}` with
`(let [c (a/chan)] (a/go (a/>! c 1)) (println (a/<!! c)))` (oracle `1`). Neither cljw
(blocked on `Long/getLong`) nor rontolisp runs it, so this would be an edge over cljw.
Scope: interpreter and JVM first; on WASM, `go` blocks over a single-threaded scheduler is a
separate decision. Every blocker is infrastructure, never special-cased.

Measured 2026-10-29 (`.kb/clojure-frontend.md`, "core.async and tools.analyzer.jvm"): the
channel half (`impl.*`: channels, buffers, dispatch pool, timers) now loads verbatim and
matches the oracle on the interpreter and the JVM. The probe stops where `clojure.core.async`
requires `impl.ioc-macros`: `go` runs tools.analyzer.jvm's `analyze` at macro time on every
body, the namespace's own ~20 `go`/`go-loop` bodies included, so nothing of core.async loads
until the analyzer runs. The premise "probe the next blockers" holds, but the analyzer is not
a library probe like the others: it is the JVM Clojure compiler's analyzer, written against
the oracle's runtime model. Plan, in order:

1. `clojure.reflect` (`.todo/g96`).
2. tools.reader loads verbatim (`.todo/g97`).
3. Core macros expand to the oracle's special forms as data, macro vars callable with
   `&form`/`&env`, and `let*`/`loop*`/`letfn*`/`case*` lowered (`.todo/g98`).
4. Here: what `analyze` reads of the runtime, at macro time only -- `all-ns`, `ns-map`,
   `ns-aliases`, `ns-name`, `the-ns`, `intern` over computed namespaces through the lowering
   callback (`ns-resolve`'s shape), the Var objects `=` across calls (`ns-safe-macro` is a set
   of vars); `clojure.lang.Compiler/LOADER` and `RT/makeClassLoader` as statics of the shipped
   `clojure.lang` namespace (the `PersistentQueue` precedent); `&env` values answering
   `Compiler$LocalBinding`'s `.hasJavaClass`/`.getJavaClass`.
5. What the go machine emits at run time: `clojure.lang.Var/getThreadBindingFrame` /
   `resetThreadBindingFrame` (the `bound-fn*` frame), `AtomicReferenceArray`, the `.`
   special form over the analyzer's static and instance calls.
6. Cost: `build-ns-map` runs over every namespace after each non-`ns-safe-macro` expansion,
   and every `go` body runs the full pass schedule in the macro-time interpreter; measure the
   load of `clojure.core.async` before deciding whether anything needs caching.

Done when the probe prints `1` on the interpreter and the JVM, with a "Library probes" row.
