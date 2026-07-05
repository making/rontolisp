> **Update 2026-07-05:** `position-if-not` and (unary-lite) `complement`
> shipped with the split-sequence e2e (.todo/54 Phase 3); the position family
> also gained `:start`/`:end`/`:from-end`/`:test-not` (see .todo/06 note).
> The rest below remains open.

# Sequence and set operation extensions

**Status:** not implemented. Medium priority — fills out the sequence manipulation toolkit.

## What's missing

RontoLisp has a solid base of sequence functions (`member`, `find`, `count`, `remove`, `delete`, `substitute`, `sort`, `union`, `intersection`, `set-difference`, `adjoin`, `every`, `some`, `notany`, `notevery`, `reduce`, `map` variants). The following are still absent:

### Missing sequence functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `mismatch` | Find first index where two sequences differ | Easy |
| `search` | Find subsequence within sequence | Easy |
| `count-if-not` | Count elements NOT satisfying predicate | Easy |
| `position-if-not` | Index of first element NOT satisfying predicate | Easy |
| `substitute-if` | Replace elements satisfying predicate | Easy |
| `substitute-if-not` | Replace elements NOT satisfying predicate | Easy |
| `nsubstitute-if` | Destructive `substitute-if` | Easy |
| `nsubstitute-if-not` | Destructive `substitute-if-not` | Easy |
| `stable-sort` | Sort preserving relative order of equals | Medium |
| `merge` | Merge two sorted sequences | Medium |
| `merge-sort` | (Not CL; often confused with `merge` + `sort`) | — |
| `coerce` (sequence-to-sequence) | Convert between sequence types | Medium |

### Missing set operations

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `set-exclusive-or` | Symmetric difference | Easy |

### Missing list functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `tree-equal` | Structural equality with custom test | Easy |
| `count-if-not` | (already listed above) | Easy |

### Implementation notes

- Most of the `*-if-not` variants are mechanical negations of existing `*-if` patterns.
- `mismatch` and `search` work on both strings and lists (rontoLisp sequences).
- `merge` requires knowing the sequence type of the result and the test predicate.
- `stable-sort` needs a stable sorting algorithm (TimSort or merge sort) instead of the current quicksort.
- All the sequence functions already implemented accept `:test` and `:key` in some capacity (see `[[06-sequence-test-key-keywords]]` for the remaining gap there).

### Implementation approach

1. `count-if-not`, `position-if-not`, `substitute-if`, `substitute-if-not`, `nsubstitute-if`, `nsubstitute-if-not` — expand as macros into existing primitives or add as builtins (interpreter + JVM + WASM).
2. `mismatch`, `search` — new builtins following the `member`/`find` pattern.
3. `merge` — new builtin with sequence type dispatch.
4. `stable-sort` — replace or add alongside `sort`.
5. `set-exclusive-or` — macro or builtin using `union`, `set-difference`, `intersection`.

### Related

- `[[06-sequence-test-key-keywords]]` (:test/:key parity)
- `[[31-lambda-list-extensions]]` (these functions use `&key`)
