# g20. Library probe batch: unprobed pure-Clojure libs

Difficulty: Medium (probes only; fixes cut follow-ups)

Several widely used pure-Clojure libs were never probed through this front
end's `deps.edn` path
(`.kb/clojure-frontend.md`, "Library probes" table): `math.combinatorics`,
`data.priority-map` / `flatland.ordered`, `data.generators`,
`test.check` + `hive-test`, `core.cache` / `memoize`, `potpuri`,
`core.unify`, `integrant` (and `qbits.ex`). The table already covers medley,
tools.cli, honeysql, hiccup, reitit-core, malli and data.json; instaparse is
in progress elsewhere (transients). Each unprobed lib is either green
verbatim -- worth pinning -- or names the next concrete blocker, which is
exactly how the last rounds moved the front end forward (destructuring `:as`,
condition maps, `StringBuilder`/`Locale` on wasm, `%clojure-shared-value`).

## Definition of done

- For each lib above (checked against the probe table at write time -- skip
  any that landed since): a scratch `deps.edn` + `probe.clj` (a dozen calls,
  per the existing probe method), run on interpreter + JVM first, then both
  wasm backends, diffed against `clj -M probe.clj` (1.12.6.1673).
- Greens join the "Library probes" table with versions; each red is either
  fixed in this item (if as small as the recent probe fixes) or cut as its
  own `.todo` item with the oracle-vs-here output quoted. None silently
  ignored.
- Transitive deps count as part of the probe (past probes showed the blocker
  can sit in a dep, e.g. `dynaload` behind malli) -- pin their coordinates too.

## Non-goals

- Fixing every blocker found: a blocker bigger than a probe fix becomes its
  own todo; this item stays a sweep.
- JVM-only libs (cheshire/Jackson, next.jdbc, clj-time/joda) and
  `core.async` -- outside the pure-Clojure frontier.
