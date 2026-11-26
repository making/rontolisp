# Command-line options: one table

`cli/CliOptionTable.OPTIONS` is the vocabulary of `rontolisp`, `rontolisp test` and
`rontolisp build`: each `cli/CliOption` holds its spellings (the first is the key
`CliOptions` stores every spelling under, so `--help` is `-h`, `--eval` is `-e`), its arity,
whether it repeats, whether only `test` takes it, its help groups and its help text.
`CliOptions.build` parses from it, `--help` prints from it, and the reference page
(`doc/<lang>/getting-started/command-line.md`) lists its groups.

- **An option the table lacks is refused** (`unknown option '--x' (try: rontolisp --help)`);
  a `test`-only one (`-r`, `--color`, `--disable-colors`) outside `test` names its command.
  Before, any key was stored and never read: a misspelt flag changed nothing and swallowed
  the next argument as its value.
- **A wasm-only option beside a JVM output is refused by name** (`--no-wasi requires a .wasm output: ...`,
  `WasmSourceCompiler.refuseConflicts`), `--component`/`--no-wasi`/`--no-gc` included: never a no-op.
  Pinned by `RontoLispCliTest#hostFetchOutputAndLoggingGuardsNameTheBackendConflicts`.
- **Arity**: `FLAG` and `OPTIONAL` take no following argument, only a glued `=value`
  (`--optimize=size`, `--help=frontend:java`); `VALUE` takes either form; `CLOJURE`
  (`-A:al`, `-M`, `-X`) is parsed before the table (`CliOptions.isClojureFlag`) and is in it
  for the help alone. `--stack` and `--buffered-output` are read off the raw arguments
  before parsing; their entries keep them accepted and documented.
- **Groups**: `common`, `frontend:<language>` (the `--source-language` names), `backend:`
  `interpreter`/`jvm`/`wasm`/`component`/`native`. An option lists every group it applies to;
  the help describes it under the first printed group and names it at the end of the others
  (`See backend:jvm for ...`), so a selection (`--help=GROUP,...`) always shows it in full.
  No help line at column 2 starts with `-` unless it is an option: the test reads the
  sections by that.
- Subcommands with a parser of their own (`format`, `notices`) keep their own `--help`; `test`
  and `build` print their own usage too, over the same table.
- Adding an option: an entry (groups included) and the row of each group in
  `doc/en` + `doc/ja` `getting-started/command-line.md`.
- Pinned by `RontoLispCliTest#theHelpGroupsTheOptionsByFrontEndAndBackEnd` (every entry in
  exactly its groups of `--help=all`, a selection, an unknown group, both doc tables equal
  to the table) and `CliOptionsTest#aTrailingValuedOptionOrAnUnknownOneIsRejected`.
