# string-downcase/-upcase/-capitalize should accept a string designator (fixes plist-alist)

**Status: TODO.** Discovered while adding the assoc-utils demo (todo 86,
`.kb/asdf.md`). `assoc-utils:plist-alist` calls `(string-downcase key)` on
keyword plist keys (`:name` -> `"name"`), but rontolisp's
`string-downcase`/`string-upcase`/`string-capitalize` require an actual string
and reject a symbol/keyword. In Common Lisp these take a **string designator**
(a string, symbol, or character), so `(string-downcase :FOO)` => `"foo"`.

Because of this, `plist-alist` is omitted from
`examples/asdf/assoc-utils-demo.lisp` (commented note there) and not exercised in
`AssocUtilsE2eTest`. Everything else in the assoc-utils read/convert API works on
all four backends.

## What to change

Make the three case functions coerce their argument through the existing string
designator logic (drop a keyword's leading `:`, like `(string :html)` => `"html"`
already does) before case-folding.

- **Interpreter** (`Environment.registerStringOps`): swap `requireString` for
  the existing `stringDesignator(...)` helper in the `STRING_UPCASE` /
  `STRING_DOWNCASE` / `STRING_CAPITALIZE` bodies. Small.
- **Compile path** (JVM `JvmStringUpcaseCompiler`, WASM
  `WasmStringUpcaseCompiler` + its `FUNC_STRING_{UPCASE,DOWNCASE,CAPITALIZE}`
  runtime helpers): the current codegen assumes the argument already carries the
  string quote marker (`"abc"`) and just `toLowerCase`s the whole thing. A
  keyword at runtime is a bare symbol (no marker, leading `:`), so it needs the
  same runtime designator coercion `string` uses (there is a `FUNC`/helper for
  `string` coercion on each backend -- route through it first). This is the real
  work: runtime type dispatch on all three compile backends, mirroring how
  `string` / `string=` already coerce.

## Finish-line (per the proven workflow)

- Verify on all four backends (interpreter / JVM / WASM P1 / component); a
  designator arg and a plain-string arg both.
- Re-enable `plist-alist` in `examples/asdf/assoc-utils-demo.lisp` (and its
  README expected-output block) + add it to `AssocUtilsE2eTest`'s exercise.
- Add a plain-Lisp ci-spec case (`(string-downcase :FOO)` etc.).
- Docs: update `string-downcase`/`string-upcase`/`string-capitalize` detail
  pages (en+ja) to note string-designator acceptance.
- No introspection-count change (no new operators).

Memory: [[asdf-library-candidates]] (assoc-utils entry notes this gap).
