# `define-library` / `include` for the Scheme front end

Difficulty: High

Split off from `.todo/826` (the `define-library` / `include` row). Today both are refused
by name (`SchemeLowering.syntaxTable`, `Core.UNSUPPORTED`), and cross-file references are
by convention: an unknown name is a direct call / a variable.

## Scope

- `include` / `include-ci` (R7RS 4.1.7): the named files' datums spliced as a `begin`,
  path relative to the including file, read through the seam's loader (the playground
  has no filesystem), positions naming the included file.
- `define-library` (R7RS 5.6): `export` (with `rename`), `import`, `begin`, `include`,
  `include-ci`, `include-library-declarations`. Found in the importing file (leading
  `define-library` forms, as Gauche allows) or as `<dir>/a/b.sld` for `(a b)`.
- A library body is lowered as a whole file with its own import scope; its top-level
  names are private (a spelling no user identifier can mangle to), exports reach the
  importer as bindings (direct call for a `defun`, variable, record predicate fused).
- Instantiated once per program, also when two separately lowered files import it.
- No backend change; a program that uses neither compiles to byte-identical output.
- `--scheme-standard r7rs`, the REPL session, `eval`'s keyword list.

## Test plan

- Failing first: `SchemeLoweringTest` (library scope, private names, export errors),
  `scheme-spec.yaml` cases on all four backends against `gosh -r7`, a multi-file E2E
  (`.sld` + `include`) on the interpreter and the compile path.
- Reference pages for the new keywords (`SchemeReferenceTest`), doc/en + doc/ja.
