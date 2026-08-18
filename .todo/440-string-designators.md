# 440. String functions do not accept string designators

Difficulty: Medium

Child of `.todo/436` (read it first). Wave 1.

## The defect

```lisp
(string-trim "*" '*foo*)   ; => STRING-TRIM expects a string, got: *FOO*
```

CL coerces a string DESIGNATOR -- a string, a symbol, or a character -- through
`string` wherever a string is expected.

## The fix

Sweep the family, not the one function that was caught: `string-trim` /
`string-left-trim` / `string-right-trim`, `string-upcase` / `-downcase` /
`-capitalize`, `string=` / `string-equal` / `string<` and friends, and the
positions in `concatenate` and `format`'s `~A` that CL specifies as designators.
Enumerate in the `.kb` file exactly how far it was widened.

Read `.kb/reader-case-upcase.md` (symbol names are upper-case canonical, so
`(string 'foo)` is `"FOO"`), `.kb/characters-code-points.md`,
`.kb/concatenate-result-families.md`.

## Watch

- Symbol -> string is `symbol-name` verbatim (upper case). Do not downcase.
- Do NOT widen into "stringify anything". Only the positions CL defines as
  designators -- losing a type error where one belongs is worse than the gap.

## Acceptance

Each touched function accepts string / symbol / character forms identically on
all four backends; a ci-spec case (`string-designators-440`).
