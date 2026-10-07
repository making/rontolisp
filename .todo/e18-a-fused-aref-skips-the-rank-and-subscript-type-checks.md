# e18. A fused aref skips the rank and subscript-type checks

Difficulty: Medium

`(defun nx (v x) (* 2 (aref v x)))` (a rank-1 aref leaf of a fused tree), against the
interpreter and `--optimize=size`, which signal:

| call | interpreter, JVM size | JVM default | WASM P1 / component, every level |
| --- | --- | --- | --- |
| `(nx (make-array '(2 2) :initial-element 1) 1)` | `aref: expected 2 subscripts, got 1` | `2` | `unreachable` trap |
| `(nx (make-array '(2 2) :element-type '(unsigned-byte 8)) 1)` | same | `0` | `unreachable` trap |
| `(nx (vector 1 2) nil)` | `AREF: The value NIL is not of type INTEGER` | `... (INTEGER 0 (2))` | as the interpreter |
| `(nx (vector 1 2 3) 2.0)` | `AREF: The value 2.0 is not of type INTEGER` | `... (INTEGER 0 (3))` | as the interpreter |

- JVM: the fused fallback (`JvmIntFusionCompiler.emitFallback`, `ArefLeaf`) calls the rank-1
  read helper (`namedAref1Helper`) alone, where the ordinary emission runs the rank check
  (`_ivCheckRank`) and the subscript check (`_ckIdx`) first. A rank-2 array's flat storage is
  read as if rank 1.
- WASM: the trap is not fusion's -- the size level traps too, so the generic rank-1 read
  of a rank-2 array under `handler-case` does not reach the condition.

Plan: a failing four-backend test of the table first; then the JVM fallback runs the ordinary
emission's checks, and the WASM rank mismatch signals the interpreter's condition.
