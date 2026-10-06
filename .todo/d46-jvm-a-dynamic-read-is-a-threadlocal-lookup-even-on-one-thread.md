# d46. JVM: a dynamic read is a ThreadLocal lookup even on one thread

Difficulty: High

Every read of a dynamically bound special on the JVM is `_dget`, a `ThreadLocal.get`
(`.kb/dynamic-special-variables.md`, "JVM"). Since a special binding has no lexical twin any
more, a closure built inside a binding reads it that way too, where it used to load a
captured cell -- the cost cl-ppcre's scanner pays: its `labels` advance function, built inside
the `let*` of `*end-pos*` and friends, does a few lookups a step.

Measured 2026-10-05 (load 5-55, best of 7 rounds): 6.4M calls of a closure built inside a
binding JVM 8-9 -> 39-74 ms (wasm, a `global.get`: 161-186 -> 127-161); 100,000 cl-ppcre scans
JVM 455-775 -> 549-927 ms.

## Plan

- A program that can run Lisp code on one thread only -- no `make-thread`, no serving, no
  `jvm-export`ed entry, no `java:proxy`/`java:reify` callback a host may call from its own
  thread -- could keep a dynamically bound special in its `_g$` static with a save/set/restore
  around the binding, as WASM does, and read it with one `getstatic`. Decide the gate
  conservatively (over-collection keeps today's ThreadLocal), measure cl-ppcre, the ci-spec
  program and the thread / served-request tests, and keep the ThreadLocal store everywhere
  the gate says a second thread can run.
