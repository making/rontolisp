# k20. Java front end: a monitor's entry and exit cost 170 ns on wasm

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.**

Measured by k07 (2026-11-19, `.kb/java-frontend.md`, "Threads", the monitor's bullet, and
"What k07 measured and made of the resumable lowering"; wasmtime 49.0.0, k00's
`bench/Counter`, 8,000,000 synchronized increments under four threads, the loop a plain
function): 1.65 s with the monitor, 0.26 s with `increment()` holding none -- 170 ns an
entry and its exit, where j76 measured 90 ns a `synchronized` round before a thread
counted the monitors it holds (carrier slot 5) and a monitor kept the threads waiting to
enter it (its seventh slot). k07 took the table's size out of the exit (30 ns:
`%java-monitors-held`). An `unwind-protect` around the region and the `mutex-release` of
the exit are not it (measured: no change without either).

What is left reads as the vectors: a carrier and a monitor are general vectors, and an
entry with its exit is some twenty `aref` / `%aset` of them (`%java-monitor-enter`,
`%java-monitor-of`, `%java-monitor-exit`, `%java-monitor-left`), each a generic access.
Leads, none measured:

- A carrier and a monitor as structures (`defstruct`: a slot is a `struct.get`), on
  every backend or on wasm alone; the host variant holds a mutex and a condition
  variable in the same vectors.
- The entry of a monitor the thread entered last, written as one function with no call
  (`%java-self`, `%java-monitor-of`).
- The JVM: 50 to 60 ns a round (j76), the same vectors as `Object[]`.

## Done when

A lead is made and the counter measured again on four backends, or the numbers say it
is not worth it, in `.kb/java-frontend.md`.
