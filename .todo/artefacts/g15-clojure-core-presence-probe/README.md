# g15 presence probe: scripts and raw results (2026-10-14)

Run against the develop jar built at `fb2133d4f`
(`target/rontolisp-0.1.0-SNAPSHOT-exec.jar`), host `clj` 1.12 for the name lists.

- `probe4.py` -- clojure.core: every public of `ClojureCoreNames`' oracle list
  (`oracle-core.txt`, 679 names) referenced in head position `(name)` and as a
  value, one JVM run each, `unknown name` in the output marking the gap. Writes
  `core-probe.json` (`name -> [value-ok, head-ok]`).
- `probe5.py` -- every public of the non-core built-in namespaces
  (`others-q.txt`, `ns/name`, 136 names), after one `require` of all of them.
  Writes `others-probe.json`.
- `core-probe.json` / `others-probe.json` -- raw results.

Result: 624/679 head-ok, 17 unsupported in both positions (the g15 todo list);
136/136 non-core names resolvable, all listed namespaces `require`-able.

Rerun: rebuild the jar, then `python3 -I probe4.py` and `python3 -I probe5.py`
from this directory (each run needs the jar path inside the script to point at
the fresh build; threads write per-thread temp `_[pq]-*.clj` files into the
cwd -- delete them afterwards, they are not artefacts).

## Re-run (2026-10-22, jar rebuilt at HEAD `5a61be256`)

- core: value-ok 638, head-ok 637; counted ok **675 of 677** (2 exempt);
  `NEITHER (counted)` is exactly `Inst add-classpath`. Fixed since baseline:
  `EMPTY-NODE`, `StackTraceElement->vec`, `amap`, `areduce`, `bound-fn`,
  `definline`, `io!`, `pvalues`, `refer-clojure`, `sync`,
  `with-loading-context`, `with-local-vars`, `with-precision` (13 of 17;
  g16 turned 118 macro names from head-only to both: 469 -> 600).
- Newly visible: 37 head-only names (all already head-only in the baseline;
  the baseline counted head position only). Of those: `unquote`,
  `unquote-splicing` (reader syntax); `deliver`, `promise` (refused until
  g35); `require`, `use`, `in-ns` (ns-wiring, head-only by design); 30 real
  functions missing value arms -- see `.todo/g39-core-function-value-arms.md`.
  Value-only (38 vars) unchanged.
- non-core: 136/136, unchanged (`others-probe.json` byte-identical, so only
  `core-probe.json` changed in this re-run).

## g26 dispositions of the deprecated/internal six (2026-10-20)

`EMPTY-NODE`, `StackTraceElement->vec`, `add-classpath`, `Inst`,
`PrintWriter-on` and `primitives-classnames` are the six of the gap that are
deprecated or JVM internals in the oracle. Faithfully reproducing them makes no
sense on any backend here, so each carries a disposition instead of a lowering
(`.todo/g26-deprecated-internal-rescope.md`, `doc/en/clojure/deviations.md`):

- Lowered, must resolve in both positions: `EMPTY-NODE` (the empty vector;
  clojure-spec `empty-node-is-the-empty-vector`), `StackTraceElement->vec`
  (the `[class method file line]` vector; clojure-spec
  `stack-trace-element-vec-refuses-nil-and-foreign-values`, host legs pinned by
  `ClojureInteropTest`).
- Documented refusals, must stay `unknown name` in both positions:
  `add-classpath` (no class loader exists here; dependencies come from
  `deps.edn`, `--java-dep` and `--java-classpath`; deprecated since 1.1 even in
  the oracle), `Inst` (the host protocol object `inst-ms*` extends, on no
  backend; `inst?` and `inst-ms` cover the predicate and the reading).
- Exempt, dropped from the gap count with the reason: `PrintWriter-on` (builds
  a host `PrintWriter` over a proxied writer; print into a string with
  `with-out-str` instead), `primitives-classnames` (the print-dup helper map
  for primitive `Class` objects, which print by name here).

`probe4.py` checks the lowered and documented sets and fails on a mismatch; the
exempt names are skipped in its gap counts. After all five g15 slices the
re-run must report every name at its natural position (functions in both
positions, non-function values as values): the counted gap is exactly the 2
documented refusals (`add-classpath`, `Inst`), with `PrintWriter-on` and
`primitives-classnames` exempt and uncounted.
