# Clojure: specter and schema on the wasm backends

Difficulty: High

Found by the j07 re-probe (2026-11-09, `.kb/clojure-frontend.md` "Library probes", the specter
and schema rows; probes in `.todo/artefacts/j07-clojure-library-probe-round-2026-11-08/`).
Both print the oracle's lines on the interpreter and the JVM class (schema up to h09's stop);
both stop on wasm P1 and the component before printing anything.

## specter 1.1.6

1. Compile time: `specter.cljc:1020:1: in macro com.rpl.specter/path: No matching field found:
   get for class clojure.lang.Symbol`. `path`'s body runs in the macro-time image (a JVM, the
   host present), but the helpers it calls are lowered for the wasm target, whose instance call
   keeps no host arm ("Host arms where the host is not"), so `(.get ^MutableCell c)` refuses.
   The question is general: what a macro body runs is the compile host's code, not the
   target's. The same stop hits schema's `s/defprotocol` expansion on wasm (j24's note).
2. Run time: the cache is a `com.rpl.specter.MutableCell` (`(MutableCell. v)`, `.get`, `.set`)
   and `fast-object-array` is `com.rpl.specter.Util/makeObjectArray` -- plain Java classes in
   specter's jar, which wasm cannot run. The jar ships their `.java` sources. Options to weigh
   (no special case per library, the probes' rule): a portable reading of a library's trivial
   Java class, the `:cljs` branch of a `.cljc` where its `:clj` branch imports a class wasm
   cannot hold (the ClojureScript-ports rule's analogue for reader conditionals), or red with a
   documented reason.

## schema 1.4.2

1. Load: `schema/utils.cljc:147`, `(java.util.Collections/synchronizedMap
   (java.util.WeakHashMap.))` -- `JAVA:STATIC`/`JAVA:NEW`, a call-time error at load. Needs a
   mutable `equals`-keyed map where no host is (the portable `IdentityHashMap` is the model;
   the `HashMap` construction is immutable there) and `synchronizedMap` of one as itself.
2. Then h09's stop on wasm too: `(class? (class f))` of a fn is false there as well
   (`class` answers `:function`; `.getClass` answers a `(:C%CLASS name)` designator), so
   `declare-class-schema!`'s assert refuses `s/defn`.

## Done when

The verbatim specter probe prints the oracle's lines on P1 and the component, and schema's
loads there and stops no earlier than it does on the host; the KB rows say so.
