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

Rerun: build the jar, then `python3 -I probe4.py` and `python3 -I probe5.py`
from this directory (each run needs the jar path inside the script to point at
the fresh build; threads write per-thread temp `.clj` files into the cwd).
