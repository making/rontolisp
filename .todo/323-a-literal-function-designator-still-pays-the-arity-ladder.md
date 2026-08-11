# A literal function designator still pays the arity ladder

Difficulty: Medium

`(mapcar #'foo lst)` compiles to a call of the arity-1 DISPATCHER even though `foo` is
named right there in the source. The map family, `reduce` and `sort` all compute
`FUNC_DISPATCH_BASE + n` unconditionally and push the function VALUE as their first
argument, so a literal `#'name` target costs a `br_table` over every callable of that
arity at run time -- and, worse, makes the callee a first-class value, which is what
puts a case in the ladder in the first place.

## Measured

zlib `--optimize=size`, 137,430 B. The ladders themselves:

| dispatcher | body | cases | code those cases call |
| --- | ---: | ---: | ---: |
| `_dispatch_1` | 2,011 | 72 | 12,197 |
| `_dispatch_2` | 2,020 | 64 | 16,481 |
| `_dispatch_3` | 901 | 8 | 8,558 |
| `_dispatch_4` | 748 | 6 | 7,371 |
| `_dispatch_5` | 898 | 7 | 7,640 |
| `_dispatch_7` | 832 | 5 | 7,129 |
| `_dispatch_9` | 904 | 5 | 7,129 |
| **total** | **8,314** | **167** | |

The bodies are the small half. The module has no table and no element section, so every
edge in it is a direct `call`, and a reachability walk over those edges says:

**205 functions, 36,526 B -- 26.6% of the artifact -- are reachable ONLY through a
ladder case.** Drop the ladders' outgoing edges and the reachable set falls from 374
functions to 167.

Not all of that is recoverable: chipz really does `funcall` its state function through a
variable, and those targets have to stay dispatchable. What is recoverable is every case
that exists because a designator the compiler could READ was routed through the ladder
anyway. `STRING=` (2,449 B) and `STRING-EQUAL` are in the artifact with no caller but the
ladders and one prelude defun, in a program that compares no strings.

## The change

`WasmMapcarCompiler`, `WasmMapcCompiler`, `WasmMapcanCompiler`, `WasmReduceCompiler` and
`WasmSortCompiler` (and the JVM twins, which have the same shape) take the designator
through `FunctionDesignators.normalize` and then always dispatch. When that designator is
a literal `#'name` or `'name` naming a defun of the right arity, emit the direct call the
head-position spelling would have emitted -- and do NOT record the funcId in
`ctx.valueFuncIds`, which is what removes the ladder case and, with it, the ladder's
reachability edge.

Watch the interactions:

- **Arity.** A variadic callee reached at arity N needs the surplus arguments linked into
  a rest list, which `WasmFunctionCallCompiler.compileDirectCall` already does; a fixed
  callee of the wrong arity must keep the old route rather than becoming a compile error,
  because the map family's contract is a run-time one.
- **Lisp-2 shadowing.** A `flet`/`labels` binding of the same name shadows the global, so
  the direct call is only right when the name resolves to `ctx.functions`, exactly as the
  head-position case decides it.
- **`dispatchableFuncIds` accounting** (`.kb/optimize-dead-code-elimination.md`): the
  point of the change is to shrink `valueFuncIds`, so re-read that file's reasoning about
  which edges keep spliced library defuns alive before assuming a case can go.
- **Byte identity is NOT expected here** -- this changes emitted code for a very common
  shape -- so the four-backend verification is the gate, not a hash comparison.

## Deliverable

Measured reductions in the `zlib` rows of `size-report/results/wasm-flags.md` with the
row's check still gunzipping byte for byte, the same treatment on the JVM backend or a
written reason it does not apply there, a pin that a literal-designator map/reduce/sort
site emits a direct call and that a computed one still dispatches, and `./mvnw test` +
native `CiSpecE2eTest` green.
