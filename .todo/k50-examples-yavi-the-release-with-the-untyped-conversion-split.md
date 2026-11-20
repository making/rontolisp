# k50. Examples: YAVI's release with the untyped-conversion split, its coordinates and sizes

Difficulty: Low

Left by k46 (2026-11-20). https://github.com/making/yavi/pull/604 (to `develop`) gives
YAVI one public conversion a type (`UntypedConversions`, `ValueValidator.untyped(Class,
Function)`), so a schema no longer reaches every type YAVI can read an input as. The
examples name `am.ik.yavi:yavi:0.18.0`, which has not got it. Measured over the pull
request's sources: `.kb/java-frontend.md`, "YAVI compiled to wasm".

- **When**: a YAVI release on Maven Central contains the pull request. Until then there is
  nothing to do; if the pull request is closed unmerged, delete this item.
- **What**: move every `0.18.0` that names YAVI's coordinates to that release
  (`grep -rn "yavi:0.18.0\|yavi-0.18.0" --exclude-dir=target .`: `examples/examples.yaml`,
  `examples/java/README.md`, `doc/en/java/index.md` + `doc/ja/java/index.md` in the same
  commit, `ExamplesE2eTest`, `.kb/jvm-export.md`, `.kb/java-frontend.md`; the `.todo/artefacts/`
  READMEs record what was measured then and stay). Measure `Member` and `Booking` again
  (wasm, component, jar; the k33 scripts in
  `.todo/artefacts/k33-yavi-compiles-to-wasm-the-milestone-and-what-stands-before-it/`)
  and write the numbers into "YAVI compiled to wasm" ("The examples", the table of
  where the bytes go), the sizes in `examples/java/README.md` ("about 660 KB and 1.2 MB")
  and the timings in the `examples.yaml` notes. `examples/java/README.md` says a schema
  reaches more because YAVI's reader names every type: rewrite or drop that sentence.
- **Verify**: `ExamplesE2eTest` with `-Drontolisp.examples.only=java/yavi/` (the four
  backends) and the expected files unchanged; `PathCitationTest`.
