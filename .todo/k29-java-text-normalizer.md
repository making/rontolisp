# k29. Java front end: `java.text.Normalizer`

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first.**

Third gap toward `.todo/k33`: YAVI's `CharSequenceConstraint` normalizes every string it
checks, NFC by default (`Normalizer.normalize(str, this.normalizerForm)`), so a program
validating one string reaches it. Measured 2026-11-19 with a stand-in that normalizes
nothing (`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`).

- `Normalizer.Form` (the four forms), `normalize`, `isNormalized`.
- The data is the Unicode Character Database's decomposition mappings, combining classes
  and composition exclusions. Generate the tables as `regex-unicode.lisp` is generated
  (`.kb/regex-engine.md`, "Tables"): from a running JDK's public API or from the UCD
  files, with the Unicode notice j93 added. The algorithm is Unicode Standard Annex #15,
  written from the annex.
- **Cost first**: the tables travel with every program that reaches `normalize`, which for
  YAVI is every program. Measure the size before choosing a representation, and a quick
  check that a string is all below U+0300 (already NFC and NFD) so that the common case
  reads no table. Whether YAVI should skip the call for such strings itself is its own
  question; the front end has to answer `Normalizer` either way.
- Done when a grid of strings prints a JDK's lines for the four forms on four backends and
  `shim/shim/Normalizer.java` is gone from the k24 artefacts.
