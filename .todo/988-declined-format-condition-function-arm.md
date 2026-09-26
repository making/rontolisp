# A declined `%format-condition` keeps its function-control arm in EH mode

Difficulty: Low

When `conditionNarrowing` declines the runtime format renderer, every `format-control` a site
can supply is a directive-free literal string or nil, so no control can be a function. The
generated `%format-condition` still carries the arm that `funcall`s a function control -- a
call through a runtime value. In a program that can make a symbol at run time (`read`), that
keeps every built-in dispatchable by name.

Measured 2026-09-26, `(print (read))` plus `(ignore-errors nil)`, `--optimize=size`:
247,657 B, against 34,981 B without the catching form. Under `SignalMessages.ENTRY_REPORT`
(`--report-locations` outside EH mode) the arm is already dropped and the same program is
42,404 B (`.kb/error-handling.md`, "Location lines on wasm-GC").

Goal: drop the arm whenever the renderer is declined, in every mode
(`LispMacroExpander.conditionReportDefuns`' `functionControls`). It changes the bytes of every
EH-mode module whose renderer is declined, which is why the first change kept it to
`ENTRY_REPORT`; confirm the byte change is wanted, then measure zlib and the size-report
programs and record them.
