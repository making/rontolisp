# e43. Clojure library loadability probes through deps.edn

Difficulty: High

The Clojure counterpart of `152` (CL library probes): with `deps.edn` working (`e37`-`e41`),
load widely used libraries verbatim, record what blocks each, and fix the blocking
INFRASTRUCTURE in the front end rather than special-casing a library.

## Probe list (verify coordinates and versions at probe time)

| Library | Coordinate | Expected gates |
|---|---|---|
| medley | `dev.weavejester/medley` | `.cljc`, small; first probe |
| tools.cli | `org.clojure/tools.cli` | `clojure.*` contrib on the source path |
| data.json | `org.clojure/data.json` | Java interop on readers/writers |
| hiccup | `hiccup/hiccup` | macros, protocols |
| camel-snake-kebab | `camel-snake-kebab/camel-snake-kebab` | `.cljc`, regex |
| honeysql | `com.github.seancorfield/honeysql` | `.cljc`, large |
| malli | `metosin/malli` | `.cljc`, heavy core coverage |
| reitit-core | `metosin/reitit-core` | Ring routing, Java interop |

Record per library: fetched (Maven/git), the first failure (namespace, form, message),
and the gap it names. Add an item per new infrastructure gap; record results in
`.kb/clojure-frontend.md`.

## Plan

1. A probe harness over a scratch `deps.edn` per library, interpreter first, then the
   other three backends for each library that loads.
2. Fix or file each gap; re-probe.
