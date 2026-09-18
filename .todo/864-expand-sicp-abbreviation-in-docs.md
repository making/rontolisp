# Docs: spell out "SICP" where a reader first meets it

Difficulty: Low

The Scheme docs say "SICP" throughout and never expand it. Write it out at the first
occurrence on each page a reader can land on directly: *Structure and Interpretation of
Computer Programs* (Abelson, Sussman), linked to the book, then "SICP". Keep the
abbreviation after that.

- Pages today (2026-09-18): `doc/{en,ja}/scheme/` `index.md`, `libraries.md`, `repl.md`,
  `sicp.md`, `standards.md`, `syntax.md`. Grep again, since `.todo/860` adds
  `scheme/reference/` pages (its `sicp` category).
- `sicp.md` gets the full explanation. The other pages expand the name once and link to
  `sicp.md`.
- The nav label (`nav.yaml`) stays short.
- Mirror en/ja in one commit. Run `DocExamplesTest` and `./mvnw -f docs-tool/pom.xml test`.
