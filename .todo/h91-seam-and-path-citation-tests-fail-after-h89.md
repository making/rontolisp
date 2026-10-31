# h91. `SourceLanguageSeamTest` and `PathCitationTest` fail after h89

Difficulty: Low

`./mvnw test` at `b8c22d574` (2026-11-01): 2 failures, both from h89's commits.

- `SourceLanguageSeamTest.userSourceReachesFormsOnlyThroughTheSeam`:
  `am.ik.rontolisp.macro.SlotProtocol <- LispReader.readAllFromString`. If it reads shipped library
  source, add it to `EXEMPT` with its reason; otherwise route it through `SourceLanguage`.
- `PathCitationTest.everyQuotedSectionTitleInSourceIsAHeadingOfThatNote`:
  `src/test/java/am/ik/rontolisp/e2e/ContextlE2eTest.java:32` cites `.kb/pretty-printer.md`,
  "Interpreter trap", which is not a heading there. Cite the heading it sits under.
