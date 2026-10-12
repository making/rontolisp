# f66. Clojure: malli 0.20.3 past the array constructors and the bit operations

Difficulty: High

Found by the library probes (`.kb/clojure-frontend.md`, "Library probes", 2026-10-12):
`metosin/malli` 0.20.3 (with fipp, test.check, edamame and tools.reader from its pom) stops at
`malli/impl/util.cljc:27:33: unknown name: object-array` (`-vmap`). `[clojure.core :as c]`, its
first gap, loads since 2026-10-12.

Patched past it locally (`(make-array Object c)`, the `:bb` branch's `(vec oa)` for
`LazilyPersistentVector/createOwning`), the next stop is
`malli/impl/regex.cljc:480:35: unknown name: bit-shift-right`.

Known needs, by the census of malli's sources: `object-array`, `into-array`, `parse-double`
(`.todo/f57`); `bit-and`, `bit-shift-left`, `bit-shift-right`, `bounded-count`, `supers`,
`ns-publics`, `ns-interns`, `all-ns`, `load-string` (`.todo/f67`); the `clojure.lang` statics
`LazilyPersistentVector/createOwning` and `MapEntry.` (the latter answers); `java.util.concurrent`
`FutureTask`/`TimeUnit` in `-run` (host only).

## Plan

1. After `.todo/f57` and the bit operations of `.todo/f67`: re-probe `validate`, `explain` +
   `malli.error/humanize`, `decode` with `string-transformer`, `form` (the probe in the kb).
2. Fix or file each gap; four backends for what loads.
