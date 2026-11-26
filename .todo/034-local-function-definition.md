# Local macro definition (`symbol-macrolet`)

**Status:** `flet`/`labels`/`macrolet` DONE 2026-07-05 (expansion route, all
backends; see `.kb/flet-labels.md`). Remaining: `symbol-macrolet` below.

`macrolet` shipped without the per-backend compiler work the approach below
predicted: `eval/UserMacroExpander` expands it away on the compile path (it
installs each local macro into the macro-time evaluator, walks the body, and
returns a `(progn ...)`), so the JVM and WASM compilers never see a `macrolet`
at all; the interpreter handles it natively in `eval/LispEvaluator`.

## What's missing

RontoLisp can define functions and macros locally within a lexical scope
(`flet`/`labels`/`macrolet`), but has no way to define local SYMBOL macros --
bindings that expand a symbol REFERENCE (not a call) into a form.

### Missing operators

| Operator | Kind | Purpose |
|----------|------|---------|
| `symbol-macrolet` | Special form | Local symbol macros: `(symbol-macrolet ((y (car x))) y)` |

Documented as unavailable in `doc/en/guides/missing-features.md` ("Other
omissions").

### Design considerations

- **`symbol-macrolet`**: Expands symbol references (not calls) to forms. Needs a
  rewrite pass over the body before evaluation/compilation. Because `macrolet`
  proved that a body-rewriting pass in `UserMacroExpander` is enough to keep the
  backends untouched, the same shape should work here: rewrite the body at
  expansion time and drop the wrapper, so no compiler gains a case.
- Scoping is the tricky part: the rewrite must stop at a shadowing binding
  (`let`/lambda parameter/`do` &c. rebinding the symbol as a variable), and a
  symbol in an assignment position (`setq`/`setf`) has to expand into a `setf`
  of the expansion rather than a plain assignment.

### Related

- `[[031-lambda-list-extensions]]` (local functions use lambda lists)
- `[[032-multiple-value-system]]` (local functions may return multiple values)
