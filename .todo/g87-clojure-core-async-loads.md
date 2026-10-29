# Clojure: core.async loads and runs through deps.edn

Difficulty: High

`{:deps {org.clojure/core.async {:mvn/version "1.6.681"}}}` with
`(let [c (a/chan)] (a/go (a/>! c 1)) (println (a/<!! c)))` (oracle `1`). Neither cljw
(blocked on `Long/getLong`) nor rontolisp runs it, so this would be an edge over cljw.
Scope: interpreter and JVM first; on WASM, `go` blocks over a single-threaded scheduler is a
separate decision. Every blocker is infrastructure, never special-cased.

Measured 2026-10-29 (`.kb/clojure-frontend.md`, "core.async and tools.analyzer.jvm"): the
channel half loads verbatim; `go` runs tools.analyzer.jvm's `analyze` at macro time on every
body. The earlier plan assumed the analyzer reads only namespace and var plumbing of the
runtime; the second measurement overturns that: its passes tag every constant with `(class x)`
and validate each tag through `maybe-class`, so with this front end's kind keywords every body
holding a string, keyword, collection or fn refuses (`Class not found: :string`), and imports,
`Class/member`, `new`, `instance?` and `catch` need classes too. `build-ns-map` also costs
7-10 s per call at the analyzer's ~45 namespaces. Done so far: computed `extends?` (the load's
first stop), `mapv`/`mapcat` lockstep, threading into a hinted step, `intern` of a symbol
carrying metadata, the `Var` members. Plan, in order:

1. `.todo/h09`: class values are `java.lang.Class` objects on the interpreter and the JVM. Then
   re-run the kb's tools.analyzer.jvm measurement: every form of its list must analyze.
2. Namespace maps at macro time: `all-ns`/`find-ns`/`the-ns` answering the lowering's current
   namespaces in the macro-time evaluator (their KNOWN list is the call site's, which predates
   `clojure.core.async`), `ns-map`/`ns-aliases` of a computed namespace through the lowering
   callback (`ClojureMacroEvaluator.Lowering`, `nsResolveForm`'s shape, or the run-time
   lowering's `%clojure-lower-at-run-time` installed there), cached per namespace state.
3. `clojure.lang.Compiler/LOADER` (a dynamic var), `RT/baseLoader`, `RT/makeClassLoader` and
   `RT/classForName` as `clojure.lang` statics (`ClojureLangClasses`); `&env` values answering
   `Compiler$LocalBinding`'s `.hasJavaClass`/`.getJavaClass`.
4. What the go machine emits: `Var/getThreadBindingFrame`/`resetThreadBindingFrame` (the
   `bound-fn*` frame), `AtomicReferenceArray`, the `.` special form over the analyzer's static
   and instance calls, the `Class` objects it embeds in `.`/`new`/`instance?`/catch forms.
5. Cost: measure the load of `clojure.core.async` (every go body runs the analyzer in the
   macro-time interpreter) before deciding what else needs caching.

Done when the probe prints `1` on the interpreter and the JVM, with a "Library probes" row.
