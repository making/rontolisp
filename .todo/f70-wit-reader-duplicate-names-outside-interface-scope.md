# f70. The WIT reader still accepts duplicate names outside interface scope

Difficulty: Low

Follow-up to f60, which refused duplicate member names inside an interface
(`record quote` + `quote: func`, in `WitParser`, in wasm-tools' words). Measured
2026-10-13 against `wasm-tools component wit` 1.254.0, the reader still accepts
shapes that tool refuses, each with its own message:

- world scope: `import a; import a;` (`interface cannot be imported more than
  once`), `import quote: func();` twice (`import \`quote\` conflicts with prior
  func of same name`), a world `type r = string;` beside `import r: func();`
  (`import \`r\` conflicts with prior type of same name`);
- top level: two `interface i` / two `world w` (`duplicate item named \`i\``);
- resource bodies: two `m: func();` (`duplicate function name \`m\``);
- function parameters: `f: func(x: string, x: u32)` (`param \`x\` is defined
  more than once`).

Deliberately NOT refused (wasm-tools accepts them, so the reader must too):
duplicate `record` fields, duplicate `enum`/`flags`/`variant` cases, one name in
two interfaces of a package.

## Plan

1. Pin each refusal above in the WIT parser's tests, in wasm-tools' words.
2. Refuse them in `WitParser`, keeping world import/export namespaces separate
   (a world may `import a;` and `export a;` — measured accepted).
