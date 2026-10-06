# d66. JVM: an export's every caller pays a ThreadLocal per multiple-value write

Difficulty: Medium

A class with a `jvm-export` keeps the multiple-value channel per thread
(`.kb/multiple-values.md`, "One register per thread"), and since a host never runs its `main`,
no thread owns the channel's static field: every write is a `ThreadLocal.set`, a host's only
thread included. A war's request threads pay the same.

Measured 2026-10-06 (best of 7 rounds, load average 18-31): an export running 20,000
`multiple-value-bind`s a call, 50 calls on ONE host thread, 35-54 ms through the shared field
(which crossed values as soon as a second thread called in) -> 91-103 ms through the
ThreadLocal; on 8 threads at once 158-166 -> 40-42 ms.

## Plan

- Let the first thread that calls an export claim `_mvOwner` once, under the class's lock --
  the way `JvmTailBounce`'s first value tail claims its count -- and never move it: a claim
  that can move strands the values a thread published as owner. Check what `main`'s
  unconditional claim does in a class that has both (a CLI tool that is also a library).
- Measure one host thread and eight, and a war under concurrent requests; keep the pin
  `JvmExportTest#anExportCalledOnSeveralThreadsKeepsEachCallsMultipleValues`.
