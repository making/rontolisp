# c04. The pure built-in fold declines every ratio

Difficulty: Medium

`PureBuiltinFolder` folds exact integer arithmetic over literal arguments but declines every
RATIO argument and result: `(/ 7 2)` stays a call, `(/ 100 5)` folds to `20`
(`.kb/pure-builtin-fold.md`, "Deliberately OUT"). The reason was the WASM ratio tier's i32
components, and the KB named the trigger "widen those components". It fired 2026-10-03: WASM
ratio components are exact integers, and a ratio literal compiles exactly on every backend
(`WasmEmitHelper.compileRatioLiteral`).

## Plan

- Admit ratios as arguments and results of the exact arithmetic group (`+ - * /`, the
  comparisons, `numerator`/`denominator`, `rational`); the `floor` family stays out under the
  multiple-values rule.
- `FoldDifferential` ratio rows (interpreter vs folded output, all four backends), with
  components past the i64 range and the printed form of a folded ratio literal
  (`WasmLiteralPrint`).
- Measure the size effect on literal ratio arithmetic in `size-report/`, `bench-report/` and
  the ci-spec corpus before deciding the rows that land.

## Pin

- `PureBuiltinFolderTest` ratio rows; ci-spec `fold-*` rows with ratio results.
