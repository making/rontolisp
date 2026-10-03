# c16. Purge numeric `.todo/NNN` citations from source, tests and docs

Difficulty: Medium

Todo numbers must not appear in source code, comments, messages, test names or test data.
c15 removed the `bNN`/`cNN` ones; the numeric `.todo/NNN` (and `.todo/NNN-title`, `todo NNN`)
citations remain: 716 lines in 156 files outside `.kb/` and `.todo/` (2026-10-03).
Heaviest: `WasmLispCompilerIntegrationTest` 78, `LispEvaluatorTest` 59, `JvmLispCompilerTest` 55,
`LispMacroExpander` 45, `ci-spec.yaml` 43, `examples/llm/README.md` 35, `PathCitationTest` 25 (its
`.todo/NNN` fixtures), `WasmLispCompiler` 25, `Environment` 18.

Also `ci-spec.yaml`-style identifiers and file names carrying a bare number
(`ci439-wr`, `c439.dat` were renamed in c15; look for more such `ciNNN`/`cNNN` prefixes).

Rewrite each citation so the comment stands without it: state the fact or measurement, and where
a pointer is still wanted, point at the `.kb/` file that holds the measurement, never the item.
`.kb/directory-rename.md` and `PathCitationTest` treat `.todo/NNN` item references as allowed by
design ("its number goes on being cited afterwards"); that rule has to change with this item
(the test's skip of item references can then become a failure, or stay as a no-op), and the
note in `.kb/` that documents it must be updated.

False positives: hex digests, commit IDs, `#NNN` in format strings.
`.kb/` and `.todo/` may cite todo numbers; leave them. Program output must not change: run every
touched test class.
