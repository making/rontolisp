# Clojure on the JVM: the first run-time verb lowers the whole program again

Difficulty: Medium

A JVM host's first lowering verb reads and lowers its whole program again from the image
(`ClojureProgramLowering.read`), so later verbs take the compile-time lowering's decisions
(`.kb/jvm-run-time-lowering.md`, "Limits"). Measured 2026-10-30 on spec's
`instrument`/`check` probe (`.kb/clojure-frontend.md`, "clojure.spec.alpha"), once each
fragment splices only what its host lacks: JFR puts 34% of the run's samples in that read,
about 3.4 s of 10 s, more than the 69 fragments' lowering, front end and compile together;
the interpreter's whole run is 7.7 s.

What to weigh, measured first (the read's wall time and where the first verb waits):

1. Starting the read on a thread of its own when the host starts, so it overlaps the
   program's run up to the first verb (the image is in memory; the read writes nothing the
   program sees): what a host that never reaches a verb pays in CPU and memory.
2. Carrying the lowering's state in the image instead of the source -- namespaces,
   mappings, the gensym counter, macro expanders (closures of the interpreter): whether
   that state can travel at all decides this one.
3. Lowering only the namespaces a verb names, on demand.

Done when the probe's first-verb cost is recorded before and after in the `.kb` file and
clojure-spec's `host:` cases stay green.
