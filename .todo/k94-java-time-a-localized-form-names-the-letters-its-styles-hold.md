# k94. java.time: a localized form names the letters its styles hold

Difficulty: Medium

**Read `.kb/java-frontend.md`, "java.time: what a formatter costs", first.**

k79 (2026-11-21) put the parts of letters no pattern names behind questions of the letters
noted, each `|| Patterns.localizes()`: a program reaching `DateTimeFormatterBuilder.parts` (a
localized form, a requested template) keeps every part, because the patterns of the locales'
styles and skeletons are read where it runs. Measured (wasm, k79's build): a date written and
read by `ofLocalizedDate(FormatStyle.MEDIUM)` 381,815 B, by `ofPattern("MMM d, y")` 337,012.

- **The lead**: a constant style names the letters of the patterns the five kinds of locale
  write for it (`Texts.styles`: `MEDIUM` of a date is `y MMM d`, `MMM d, y`, `d MMM y`), a
  constant template those of the skeletons it can match, and `localizes()` goes. The data
  lives in the library's Java source; a copy in `JavaIntrinsics` needs a test holding it to
  the library's, or the front end reads the library's constants.
- **Measure first**: how much of the 44.8 KB is the parts and how much `Step.Styled` and the
  styles of five locales; then whether a template's naming is worth its own table.
