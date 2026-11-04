# i48. No reader honors `*read-base*`

Difficulty: Medium

Measured 2026-11-05, against SBCL 2.2.9. The variable exists and is special on all four backends, but every
reader reads integers in base 10.

```lisp
(let ((*read-base* 16)) (read-from-string "ff"))  ; SBCL 255, here the symbol FF
(let ((*read-base* 2)) (read-from-string "101"))  ; SBCL 5, here 101
```

Consumers: babel's `#\` reader (`src/sharp-backslash.lisp:36`), cl-qrencode's tests
(`test/encode-test.lisp:44`), clsql, which binds it to 10 and is unaffected.

Three number parsers: `LispLexer` (interpreter, and the compile paths' source read through `ReadTimeImage`), JVM
`_classify` (`JvmReadRuntimeBuilder`), WASM `emitTryInteger` (`WasmReadRuntimeBuilder`). A trailing `.` stays decimal
(CLHS 2.3.1), and a float stays decimal. The emitted readers cost a variable read only in a program naming it
(`.kb/read-load-streams.md`). A program that has a reader but names nothing stays byte-identical.
