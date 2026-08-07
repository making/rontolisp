# The fixed string-table prologue is pinned in every module

Difficulty: Low

`StringTable`'s constructor interns 28 fixed entries -- `NIL`, `(`, `)`, ` `, ` . `,
`\n`, `#<function>`, `#<FUTURE>`, `#(`, `#A(`, `#d(`, `#f(`, `-`, `.`, `/`, `NaN`,
`Infinity`, `E`, `#\`, and the eight character names (`Space` .. `Rubout`), plus `T`.
Every one of them is a printer string, and they are interned OUTSIDE the attributing
window (`StringTable.attributing`), so the blob shaker's rule -- "a range is droppable
only when the bodies that interned it are all dead" -- can never reach them: nothing
recorded them as shakeable in the first place.

The cost is ~104 B of data in a module that may use one of the entries. Measured
2026-08-06 on `(princ "Hello World!") (terpri)` at `--optimize`, 648 B total (625 B
since the encoding minimization -- the DATA is untouched by it, so the ~104 B is now
a larger share of the module):

```
(data (;3;) (i32.const 256) "NIL()  . \0a#<function>#<FUTURE>#(#A(#d(#f(-./NaNInfinityE#\5cSpaceNewlineTabReturnPageBackspaceNulRuboutT")
(data (;4;) (i32.const 1104) "\22keyword\22\22Hello World!\22")
```

The program uses `\n` and the interior of `"Hello World!"`. Everything else in
segment 3 is dead, and `"keyword"` in segment 4 looks dead too (worth confirming
where it is interned -- the keyword-package intern gate is the suspect).

## What to do

The entries are all owned by RUNTIME bodies (`_print_val` / `_princ_val` /
`_print_f64` / `_char_*` / the array printer), which is exactly the
`WasmTreeShaker.OwnedDataSegment` shape the case-fold tables already use. Either
attribute each prologue entry to the runtime function that reads it, or intern them
lazily on first use inside the attributing window. `\n` is the one entry a literal
write reaches directly (`WasmLiteralPrint.emitNewline`), so it must stay reachable
from a user body, not only from the printer.

## Non-goals

- The type section and the data-segment machinery themselves; both already shake
  (`.kb/optimize-dead-code-elimination.md`).
- The component wrapper floor (~1.5 KB), which is a different budget.
