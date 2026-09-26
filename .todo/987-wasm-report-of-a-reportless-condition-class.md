# A wasm-GC uncaught report of a condition class with no report prints no text

Difficulty: Medium

In EH mode (and under `--report-locations` outside it) a condition whose class has no
`:report` of its own or inherited reaches the entry landing pad with an empty text:

```lisp
(define-condition plain-err (error) ())
(defun f (x) (if x (error 'plain-err)))
(f (read-line))
```

- interpreter: `Unhandled condition: Condition (PLAIN-ERR) was signalled.`
- wasm-GC (`(ignore-errors nil)` added, or `--report-locations=line`): `Unhandled condition: `

`(error 'type-error :datum x :expected-type 'integer)` is the same (the interpreter prints
`Condition (TYPE-ERROR :DATUM "abc" :EXPECTED-TYPE INTEGER) was signalled.`).

Cause (2026-09-26): with the report renderer in (`routesConditionReports`),
`WasmErrorCompiler.compileCond` skips the message operand, and `%condition-report-str`
answers nil for such a class, so both halves the pad reads
(`WasmUncaughtReportCompiler.reportForm`) are nil. The signal-site expansion already builds
the interpreter's fallback text (`LispMacroExpander`, the typed-signal message) -- it is just
never compiled.

Fixing it changes the bytes of EH-mode modules that can construct such a class; measure what
it adds (zlib, cl-postgres) before choosing between compiling the message for report-less
classes only and a fallback arm in the pad. `.kb/error-handling.md`, "An uncaught condition
reports ONE line" ("Known gap").
