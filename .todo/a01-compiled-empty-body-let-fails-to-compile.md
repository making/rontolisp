# A let / let* with an empty body fails to compile on the JVM and WASM backends

Difficulty: Low

CLHS: `let` with no body forms returns nil. The interpreter answers `NIL`; both compilers
reject the program. Measured 2026-09-26 (develop after 997):

```lisp
(print (let ((p 1))))    ; JVM: operand-stack model: underflow at 6 (opcode 0x3a)
                         ; WASM: WasmRefTypeFolder: operand stack underflow at 580
(let ((p 1)))            ; same, top level
(defun f () (let ((p 1)))) (print (f))   ; same inside a defun
(print (let* ((p 1))))   ; same
(print (let ()))         ; JVM: underflow at 0
```

`(let ((p 1)) (declare (ignore p)))` compiles. The body lowering pushes no value for an
empty `progn` tail; the fix belongs where the bindings' body is compiled (both backends), not
in a macro that appends `nil`. Pin it with a ci-spec case.
