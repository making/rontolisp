# Clojure: `(set! (.field obj) v)` on a host object is a lowering error, so schema cannot load

Difficulty: Medium

schema 1.4.2, `schema/macros.clj:416-463` (`-instrument-protocol-method`, used by `s/defprotocol`
to validate protocol method calls): it wraps protocol methods and keeps the wrapper's method cache
in sync through Clojure's internal `AFunction.__methodImplCache` field:

```clojure
(set! (.__methodImplCache inner-mth) (.__methodImplCache outer-mth))
```

The whole namespace stops loading at `macros.clj:416:1: Invalid assignment target`
(`ClojureProtocolLowering`), although nothing calls it until a program uses `s/defprotocol`
with instrumentation. (The function is skipped under babashka: `(if-bb nil (defn ...))`.)

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defn g [^clojure.lang.AFunction f] (set! (.__methodImplCache f) nil) :set)
(prn :loaded (try (g inc) (catch Throwable e :threw)))
```

- oracle: `:loaded :set`
- interpreter and JVM class: `error: f.clj:1:37: Invalid assignment target`

## Two parts

1. **Lowering**: a `set!` whose target is a host instance field `(.f x)` / `(. x f)` /
   `(.-f x)` of an object that is not a deftype's mutable field must lower to a call-time
   assignment (a host field write through the `java:` boundary where the host is, a call-time
   refusal where it is not) -- the "refused verbs refuse at call time" rule (KB, the g30
   paragraph: integrant's load). Then schema LOADS.
2. **`__methodImplCache`**: protocol dispatch here is not the oracle's per-fn cache, so the field
   does not exist on a fn value here. Decide what `s/defprotocol` instrumentation does: the
   wrapper replaces the method var's root (`alter-var-root method-var (fn [_] outer-mth)`) --
   if protocol calls here go through the var root, the instrumentation may work with the cache
   sync made a no-op; if they do not, `s/defprotocol` cannot validate, and the KB plus
   `doc/{en,ja}/clojure/deviations.md` say so. Measure with schema's own
   `s/defprotocol` example under `s/with-fn-validation`.

## Done when

- The repro prints `:loaded :set` or `:loaded :threw` (part 1; which one depends on part 2's
  decision for a fn value) on all four backends, as a ci-spec case.
- schema loads without the `(comment ...)` hunk of j07's `schema/patches.diff`, and
  `s/defprotocol` with a schema'd method either validates like the oracle or is a documented
  deviation.
