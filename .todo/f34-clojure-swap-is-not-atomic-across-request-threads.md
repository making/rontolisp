# f34. Clojure `swap!` and `compare-and-set!` are not atomic across request threads

Difficulty: Medium

A Ring handler runs one thread per request on the interpreter and the JVM
(`.kb/concurrent-served-requests.md`), and an atom is how a Clojure program shares state
between requests. `swap!` lowers to read the cell, apply the function, write the cell
(`ClojureStateLowering.swapOf`/`swapValue`, `atomPut`); `compare-and-set!` compares and
writes the same way. Two requests swapping one atom at once lose an update (a hit counter
under a burst counts fewer hits than requests). The oracle retries a compare-and-set until
it wins.

Found while reading the code, not reproduced.

## Plan

1. Reproduce: a burst of requests each `(swap! hits inc)` once; the final count must equal
   the request count (`ClojureRuntimeTablesConcurrentRequestsTest`'s shape).
2. Make the cell write a compare-and-set where there are threads (interpreter, JVM): no CL
   primitive exists, so either a core CAS on a vector slot on both host backends (wasm: a
   plain write, single-threaded) or the atom's own reentrant mutex around the read, the
   apply and the write -- the oracle's retry loop runs the function outside any lock and may
   run it again, so a CAS keeps its semantics where a mutex would not. Pin on all four
   backends.
