# The condition-floor remainder: the routing gate, %print-object-str, data-section dedup

Difficulty: Medium

What is left of `.todo/316`'s decomposition after its close (see `git log`). Baseline
after 316: `zlib --optimize=size` = 194,107 B (was 411,948); the bare handler-case
probe = 23,341 B (was 89,138, mostly the `needsRuntimeErrorDispatch` clause-head
misread that 316 fixed).

## 1. `mayCreateConditions` still treats any handler-case head as constructing

`constructsInstance` lists `HANDLER_CASE`/`IGNORE_ERRORS` unconditionally, so a
handler over a body that provably cannot signal still routes condition reports and
carries `%condition-report-str`/`%print-object-str`/the simple-* layouts. Narrowing
must respect the handlers-fall-back contract (`.kb/error-handling.md`): the WASM/JVM
handler prologue synthesizes a `simple-error` for ANY caught raw trap, so "cannot
signal" has to cover runtime traps (division, array bounds, ...), not just `error`
sites -- that is why 316 left it alone. Measure first: the probe's remaining 23 KB
also contains EH plumbing and the funcall dispatcher, so the winnable slice may be
small.

## 2. `%print-object-str` on `routesConditionReports()` alone

Still emitted with empty `printObjectTags` and no printing operator reachable from a
condition value. Worth ~165 B on zlib (measured in 316) plus whatever
`%condition-report-str` anchors on programs that never print; only worth doing if 1
lands and the report path can be proven unreachable (an UNCAUGHT typed signal prints
through `routeReport` at the signal site, so zlib-shaped programs keep it).

## 3. Data-section dedup (from 315/316 measurements)

Duplicated strings total ~3,085 B upper bound on the zlib artifact (both colon
spellings of accessor names dominate); two `"No applicable method: <NAME> on "`
literals. An interning-layer dedup (share the member-name suffix between `PKG:X` and
`PKG::X` rows) would need the string table to support overlapping entries, which it
already does for substrings -- check `StringTable.addString`'s dedup rule first.

## Deliverable

Same as 316's: measured reductions in `size-report/results/wasm-flags.md` rows, no
change in what any row checks, `./mvnw test` + native `CiSpecE2eTest` green,
byte-identical output for programs that do not use the mechanism being gated.
