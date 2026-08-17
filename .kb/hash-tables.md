# Hash tables

Interpreter/JVM use a real `LispHashTable`/`LinkedHashMap` (O(1)); WASM is a true open-chaining hash table (`WasmHashTableCompiler`), not the old O(n) alist. A table is a `TYPE_CELL` box (so `consp` is nil) holding a header `cons (count . buckets)`: `count` is an i31 of live entries (O(1) `hash-table-count`), `buckets` is a `TYPE_HASH_BUCKETS` array (`array (mut (ref null eq))`, index 33, a bare array comptype after `TYPE_CHAR`; implicitly `<: eq` so it stores in a cons field). Each bucket slot is a `(key . value)` alist or nil; a key's slot is `(_hash(key) & 0x7fffffff) % capacity`. General arrays share that box, so `hash-table-p` is a `ref.test TYPE_CELL` PLUS the header-car test that separates the two (an i31 count here, a dims array there) -- the same discrimination the printer makes.

**Invariant**: `_hash` (`FUNC_HASH`, always emitted, signature `((ref null eq)) -> i32` = `TYPE_RAT_GET`) must agree with `_equal` — equal keys hash equal; it folds i31/char/string-content-bytes/float-bits/ratio and recurses on conses, with a constant-0 fallback for identity-compared values (e.g. closures). Strings/symbols fold their content bytes (`h = h*31 + byte`) because `_equal` compares string content via `_string_eq` (not interned offsets), so runtime-built strings work as keys equal to literals (see `.kb/json.md`). Keys are still compared with `_equal` within a bucket. An INSTANCE key follows the same
rule: `equal` on instances is structural (`.kb/instance-syntax.md`), so `_hash` folds the
layout tag plus the slot values on WASM, `LispInstance.hashCode` does on the interpreter,
and the JVM table keys by printed text, which agrees for free -- two separately
constructed instances with equal slots therefore find each other in an `equal` table on
all four backends (pinned by the ci-spec case `instance-print-syntax-and-identity`). `puthash` grows (doubles, `FUNC_HASH_RESIZE`) past load factor 0.75. `FUNC_HASH`/`FUNC_HASH_RESIZE` sit just before `FUNC_USER_BASE`; both are present in Preview 1 and `--component` (no import/`FUNC_START` index shift, so the component blobs are unaffected). `maphash` order is unspecified (README) -- interpreter and JVM both walk insertion order, WASM walks bucket order.

**Printing**: a table is OPAQUE -- `#<HASH-TABLE>` on all four backends, through
`print`/`princ`/`prin1`/`princ-to-string`/`format ~A`/`~S`, nested positions included
(pinned by the ci-spec case `hash-table-print-syntax` plus one test per backend). No
entry content and, deliberately, none of SBCL's `:TEST`/`:COUNT` detail: the count is
O(1) everywhere, but the test is not stored at all on WASM (`make-hash-table` ignores
`:test`, lookup is always structural), so printing it would be a divergence by
construction -- widen the tag only if the WASM table first learns its test, and never
with an identity hash, which is text that varies between runs of one program
(`.kb/emitted-output-determinism.md`). Each backend reaches the tag its own way and each
way was a defect before `.todo/430`:

- interpreter: `LispHashTable.print()`.
- JVM: a `_lispToString`/`_lispToDisplayString` arm keyed on
  `JvmHashRuntimeBuilder.MAP_CLASS` = `java.util.LinkedHashMap`, deliberately NOT the
  plain `HashMap` a `java:` call can hand back. That class is the discriminator `_hashP`
  and the printer share, so a host map stays a host object (`hash-table-p` nil, printed
  `#<java java.util.HashMap>`, both matching the interpreter) instead of impersonating a
  Lisp table; being LINKED is the other half of the choice, and is why JVM `maphash`
  walks insertion order like the interpreter. Before the arm existed a table fell through
  to `Object.toString` and printed Java's own map syntax -- braces, the raw `Object[]`
  entry pair, and an identity hash.
- WASM: the non-array arm of the shared `TYPE_CELL` branch
  (`WasmRuntimeBuilder.emitPrintArray`, used by `_print_val` and `_princ_val` alike).
  **That branch must RETURN for every cell.** A cell that falls out of it lands in the
  cons tail, which prints `" . "` and re-enters the printer on the SAME value: unbounded
  recursion, i.e. an unrecoverable `call stack exhausted` trap that also loses the stdout
  buffered before it. That is why the arm answers for any cell, not only for one that
  passes the full `hash-table-p` test.
