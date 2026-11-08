# Clojure: `System/nanoTime` and `java.util.IdentityHashMap` on wasm, and IdentityHashMap identity on the host

Difficulty: Medium

editscript 0.8.0 (j07 round) prints the oracle's 7 lines on the interpreter and the JVM class,
but both wasm backends compile with `JAVA:STATIC`/`JAVA:NEW` call-time warnings and stop at run
time on its first `diff`:

1. `editscript/util/common.cljc:38` `(quot (System/nanoTime) 1000000)` (`current-time`, the
   A* differ's timeout clock) -- `Unhandled condition: The function JAVA:STATIC is undefined`.
2. With that patched: `editscript/core.cljc:118` and `editscript/util/index.cljc:26`
   `(IdentityHashMap.)` with `.put`/`.get` -- the per-diff caches of subtree sizes and index
   metadata, keyed by node identity -- `The function JAVA:NEW is undefined`.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(prn (pos? (System/nanoTime)))
(let [m (java.util.IdentityHashMap.) k (String. "a")]
  (.put m k 1)
  (prn (.get m k) (.get m (String. "a")) (.size m)))
```

- oracle: `true` then `1 nil 1`
- interpreter and JVM class: `true` then `nil nil 1` -- a SECOND defect on the host targets: the
  key put into the Java map is not the key looked up, because each crossing of the `java:`
  boundary converts the Clojure string into a new Java string. Any Clojure value used as an
  `IdentityHashMap` key (editscript's are vectors and maps) misses the same way; editscript's
  answers stay right only because the map is a cache (every lookup misses, the value is
  recomputed: quadratic where the library is linear).
- wasm P1 (`-o p.wasm`, `wasmtime run`): line 1 traps (`JAVA:STATIC`); `(System/currentTimeMillis)`
  and `(Math/abs -3)` already work there.

## Direction

- `System/nanoTime`: a portable static like `currentTimeMillis` (WASI `clock_time_get` with the
  monotonic clock id; the component's `wasi:clocks/monotonic-clock`). KB "Portable number
  statics"/time builtins (`.kb/time-environment-builtins.md`) say where these live.
- `IdentityHashMap`: a portable identity-keyed table value (the hash-table machinery has
  `eq`-tables and `%identity-hash`, KB `.kb/hash-tables.md`) answering `.put`/`.get`/
  `.containsKey`/`.remove`/`.size`/`.clear`, used on EVERY backend instead of the host class --
  which also fixes the host-side identity loss, since the keys never cross the boundary. Check
  the same identity question for `java.util.WeakHashMap` and `HashMap` keyed by Clojure values
  (KB "HashMap construction" covers `HashMap`).

## Done when

- The repro prints the oracle's lines on all four backends (ci-spec case).
- editscript's probe (j07 `editscript/`) prints the oracle's lines verbatim on P1 and the
  component, warning-free on its path.
