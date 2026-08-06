# wasm string-upcase / string-downcase / string-capitalize fold ASCII only

Difficulty: Medium

## Symptom (pre-existing; found 2026-08-06 while splitting the case-fold tables into
## their own data segments, reproduced with the unmodified prior build)

```lisp
(print (string-downcase "ÉΛΩ"))     ; interpreter/JVM: "éλω"   wasm: "ÉΛΩ"
(print (string-upcase "éλω"))       ; interpreter/JVM: "ÉΛΩ"   wasm: "éλω"
(print (string-capitalize "élan vital")) ; interpreter/JVM: "Élan Vital"  wasm: "éLan Vital"
```

`char-upcase` / `char-downcase` are correct on wasm (full-Unicode, backed by the
`WasmCaseFoldRuntimeBuilder` range tables), so the string-level operators evidently
still run the old ASCII ±32 loop instead of calling `FUNC_CHAR_UPCASE` /
`FUNC_CHAR_DOWNCASE` per character. Interpreter and JVM agree with each other and
with CL; the wasm GC backend (Preview 1 AND `--component`) diverges — a violation of
the "identical on all four backends" governing rule. `--no-gc` not yet checked; check
it first.

## Fix sketch

Route the wasm string case loops through the two case-fold helpers (they are cheap:
depth-10 binary search). Start with a failing cross-backend test (ci-spec case or
`WasmLispCompilerIntegrationTest`) per the bug-fix policy. Note the helpers' range
tables live in their own data segments owned by the helpers
(`WasmTreeShaker.OwnedDataSegment`), so wiring the string ops to them keeps the
segments alive exactly when needed — no shaker change required.

Watch out: a supplementary-plane code point occupies one slot (`.kb/characters-code-points.md`
if present; `LispString` stores code points, one per slot), so the loop must fold code
points, not UTF-8 bytes.
