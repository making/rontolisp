# d28. A nil `:start` to a bounded string operator reads as 0 on the compile paths

Difficulty: Low

`(write-string "hello" o :start nil)` with the nil read at run time:

| backend | `write-string` / `write-line` | `(funcall #'string-upcase "hello" :start nil)` |
|---|---|---|
| SBCL | `type-error`, datum `NIL` | `type-error`, datum `NIL` |
| interpreter | `type-error`, datum `NIL` (`SUBSEQ: invalid bounds NIL, 5 ...`) | same |
| JVM, wasm P1, component | writes `"hello"` | `"HELLO"` |

A nil `:end` means the string's length; a nil `:start` is no bound at all. Two places read
it as 0:

- `LispMacroExpander.lowerWriteStringBounds` binds `(or start 0)` (`write-line` lowers onto
  it), where a given `:start` should reach `subseq` as written.
- `BuiltinFunctionWrappers.optionalStreamBounded` (and the case conversions' first-class
  wrappers) default `:start` through `getfKwOr`, `(if (getf kw :start) (getf kw :start) 0)`,
  which cannot tell an absent `:start` from a nil one. A `getf` with a default
  (`(getf kw :start 0)`) can; mind the lowercase/upcased indicator pair `getfKw` probes.

A call-position `string-upcase` / `-downcase` / `-capitalize` already refuses it
(`expandBoundedCaseConversion` passes the value through). Pin the nil `:start` in
`BoundedStringBoundsFixture` (direct and `funcall` rows) and ci-spec
`subseq-refuses-a-non-integer-bound`. `.kb/subseq-runtime.md`, "Bounded string operators",
records the gap.
