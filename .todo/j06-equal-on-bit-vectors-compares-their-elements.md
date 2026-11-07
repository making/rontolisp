# j06. `equal` on bit vectors compares their elements

Difficulty: Medium

CLHS 5.3: `equal` compares strings and bit vectors element by element with `eql` (a fill pointer
limits the elements examined) and any other array by identity. rontolisp compares a bit vector by
identity on all four backends (measured 2026-11-08):

```lisp
(list (equal #*101 #*101)
      (equal (make-array 3 :element-type 'bit :initial-contents '(1 0 1)) #*101)
      (equalp #*101 #*101)
      (let ((h (make-hash-table :test 'equal)))
        (setf (gethash #*10 h) 1)
        (gethash (copy-seq #*10) h)))
;; rontolisp, all four: (NIL NIL T NIL); SBCL 2.2.9: (T T T 1)
```

A bit vector is the general boxed array stamped `bit` (`.kb/array-literals.md`, "The degraded
array REMEMBERS its element type"): `LispArray`'s element-type field, the JVM header slot 4 (a
displaced view reads its target's), wasm's meta marker word. On every backend together:

- `equal`: two rank-1 arrays stamped `bit` compare their active elements with `eql` --
  `LispEquality.equal`, the JVM `_equal` (`JvmNumericRuntimeBuilder`), wasm's `_equal`
  (`WasmRuntimeBuilder`).
- The `equal` hash agrees: a bit vector hashes by content in `LispEquality.hash`, the JVM `_hash`
  (`JvmHashRuntimeBuilder`) and wasm's hash body, under the depth cap and work budget
  (`.kb/hash-tables.md`).
- The bit code is gated per width on the compile paths (`makeArrayElementTypeCodes`): a program
  with no bit vector compiles as before.

Pin: the program above as a ci-spec line beside `equalp-arrays-elementwise`.
