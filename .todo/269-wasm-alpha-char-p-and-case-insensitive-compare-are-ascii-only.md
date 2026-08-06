# wasm alpha-char-p / string-equal / char-equal still fold or classify ASCII only

Difficulty: Medium

## Symptom (pre-existing; found 2026-08-06 while widening the string case operators)

```lisp
(print (alpha-char-p #\é))            ; interpreter/JVM: T    wasm: NIL
(print (string-equal "ÉCOLE" "école")) ; interpreter/JVM: T    wasm: NIL
(print (char-equal #\É #\é))           ; interpreter/JVM: T    wasm: NIL
```

The interpreter classifies with `Character.isLetter(int)` and compares with
`String.equalsIgnoreCase` / `Character.toLowerCase(int)`. On the wasm GC backend
(Preview 1 AND `--component`) `WasmCharCompiler.compileAlphaCharP` tests only
`A-Z` / `a-z` inline, and `WasmStringRuntimeBuilder.emitMaybeLower` (used by
`buildStringEqBody(ignoreCase = true)`) adds 32 only for `A-Z` on a per-BYTE
walk. A violation of the "identical on all four backends" governing rule.

Knock-on: the prelude `alphanumericp` is `(or (alpha-char-p c) (digit-char-p c))`,
so it inherits the `alpha-char-p` gap; check `digit-char-p`'s radix walk on wasm
in the same pass. `--no-gc` not yet checked; check it first.

## Fix sketch

The machinery already exists after todo 267. `WasmCaseFoldRuntimeBuilder` now
emits a compressed `(from, to)` PAIR membership table plus a shared binary-search
body (`buildAlnumBody` / `buildSearchBody`), each in its own
`WasmTreeShaker.OwnedDataSegment` owned by its sole reader, so:

- `alpha-char-p` wants a second membership table generated from
  `Character.isLetter(int)` (677 ranges, ~5.4 KB) behind a `_char_alpha_p` helper
  in the same shape as `_char_alnum_p`.
- The case-insensitive compare wants to stop folding bytes: decode each 1-4 byte
  UTF-8 sequence and route the code point through `FUNC_CHAR_DOWNCASE`, the way
  `WasmStringRuntimeBuilder.emitCaseFoldCore` already does for the string case
  operators. Note that two strings that are `string-equal` can then have
  DIFFERENT byte lengths, so the current length-equality fast path has to become
  a per-character walk.

Start with a failing cross-backend test (ci-spec case or
`WasmLispCompilerIntegrationTest`) per the bug-fix policy. Mechanics and the
remaining-divergence note: `.kb/characters-code-points.md`.
