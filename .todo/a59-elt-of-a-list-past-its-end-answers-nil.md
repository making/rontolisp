# elt of a list past its end, or at a negative index, answers instead of signalling

Difficulty: Medium

Measured 2026-09-27 on all four backends: `(elt '(1 2) 5)` answers `NIL` and `(elt '(1 2) -1)`
answers `1`. CL: `elt` signals a `type-error` for an index that is not a valid sequence index (SBCL
reports the index too large for the list's length); a vector's `elt` already reports
`AREF: The value 5 is not of type (INTEGER 0 (2))`. The list arm is `nth`, which answers nil past
the end and whose negative index reached the list itself.

**Plan revised 2026-09-27 -- the original plan below does not work; see
`.kb/error-handling.md`, "elt of a LIST past its end, or at a negative index" for the full
measurement.** A `LispMacroExpander.expandElt` fix -- expanding the list arm into a `do` loop
that walks the list decrementing a copy of the index and eagerly signals
`(error 'type-error ...)` when it runs out -- compiles and reads correctly in isolation, but its
blast radius is unbounded: `elt` is read by other library bodies the compiler injects
UNCONDITIONALLY (`BuiltinFunctionWrappers`' `findFamily` wrapper for
`#'find`/`#'find-if`/`#'find-if-not`, spliced into EVERY compiled program, not just one that
references `elt` or `find`), invisible to the `mayCreateInstances`/`conditionNarrowing`/
`WasmLispCompiler.usedLayoutTags` gates that decide whether a `type-error` layout exists at all.
The eager signal made even `(print 42)` fail to compile on wasm. Gating `elt` the way `aref` was
gated for `.todo/a58` only moves the hole to the next unaudited reader (`LispPreludeLibrary`'s
`sort`/`merge`/`search`, `usocket`/`uiop-utility`/`tokenizers`, `HostFetchLibrary`'s HTTP header
reader all read a sequence with `(elt seq i)` too).

Goal (revised): give the list arm of `elt` the SAME architecture `aref`'s own bound check
already uses -- a RAW host failure the interpreter/JVM/wasm each compile directly
(`Environment.subscriptValue`/`OperandTypeException.outOfRange` for the interpreter,
`JvmOperandTypeRuntime`'s `_oob` for the JVM, `WasmOperandTypes`'s index arm for wasm),
classified into a catchable `type-error` instance lazily -- only behind a handler landing pad
(`rawFailureConditionClasses`) -- never an eager `(error ...)` a compiled site always carries.
Concretely: a dedicated list-walk primitive (following the `NTHCDR` precedent --
`JvmNthcdrCompiler`/`WasmNthcdrCompiler`/the interpreter's own walk) that counts steps while
decrementing the index and, on running out, reports through the SAME out-of-range machinery
`aref` calls (dynamic bound = the counted length), with no separate `length` walk on the hit
path (the walk that reads the element is the walk that would report running out). This needs a
change in all three backends plus the interpreter (`.kb/adding-primitives.md`, "Adding a
Built-in Function"), not a single `LispMacroExpander` expansion -- hence Medium, not Low.
