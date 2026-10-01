# Pin catalog `index_page` ⊆ nav pages in the root reactor (docs-tool follow-up)

Difficulty: Small (one root-suite test over `doc/`, no language change).

## Gap (hit twice on 2026-10-01; `docs-tool` is outside the root reactor)

`DocGenTest.generateSite` fails the `docs-tool` package build when a
`_catalog.yaml` category names an `index_page` no `nav.yaml` registers:

- b18 added `clojure/reference/names.md` (fixed in `39cf7225b`)
- b21 added `clojure/reference/regex.md` (fixed alongside this todo)

Subagent workers run at most ONE `./mvnw ... test` on the root reactor,
which never executes `docs-tool` — so the breakage lands on whoever runs
`./mvnw -f docs-tool/pom.xml package` next. The root suite already has
the precedent shape (`PathCitationTest.theTodoDirectoryHoldsNoItemNumberedDirectories`).

## Design sketch

- A root test (next to `PathCitationTest`) that discovers every
  `doc/{en,ja}/**/_catalog.yaml`, collects `index_page` values, and
  asserts each is a `file:` page in the matching `doc/{en,ja}/nav.yaml`.
  Pure file IO, no flexmark/snakeyaml dependency (parse the two line
  shapes with a regex, like `DocGen`'s own test does with `ID`/`HREF`).
- Keep the `docs-tool`-side `IOException` as the second wall (it renders
  the site and catches more than the pin).

## Acceptance

- New unit test green; a probe that removes one `nav.yaml` entry fails
  with the rule (not a generic assertion).
- `.kb/documentation-site.md` notes the pin (one line + test name).
