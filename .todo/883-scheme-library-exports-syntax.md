# Scheme: a `define-library` exports syntax

Difficulty: High

Follow-up of `.todo/882` (`.kb/scheme-frontend.md`, "Libraries and include"). Today
`(export m)` of a `define-syntax` macro is refused by name
(`SchemeLowering.exports`, `SchemeExpander.definesSyntax`). R7RS libraries routinely
export macros, so this is the largest gap left in `define-library`.

## What it needs

- The importer's expander must see the library's `Macro` meaning with the library's
  definition `Env` (a free template identifier means what it meant IN the library).
- A free template identifier that resolves to a library PRIVATE name (`s%%(a b)name`)
  must reach the importer's lowering as that private binding -- a direct call for a
  `defun`, a variable otherwise, a fused record predicate. Today the expander emits a
  free top-level name by its spelling and the lowering resolves it in the importer's
  scope, where the private name does not exist. One way: the expander emits a generated
  identity symbol the lowering maps to the library's `Binding`.
- `only` / `except` / `prefix` / `rename` over a set holding syntax; a session importing
  a library that exports syntax.
- Byte-identical output for a program that exports no syntax (measure).

## Test plan

- Failing first: `SchemeLibrariesTest` (a macro using a private helper, a macro binding a
  variable the importer also uses -- hygiene across the boundary), `scheme-spec.yaml`
  standalone cases on all four backends against `gosh -r7`.
