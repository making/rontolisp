# h56. JVM: a `nil` key in an `eq`/`eql` hash table throws `NullPointerException`

Difficulty: Low

Found 2026-10-30 compiling fiveam (h30) to a `.class`: `suite.lisp` runs
`(setf (get-test 'nil) ...)`, a `gethash` store under `nil` in an `eql` table.

```lisp
(let ((h (make-hash-table :test 'eql))) (setf (gethash nil h) 1) (print (gethash nil h)))
; interpreter, P1, component: 1
; JVM: NullPointerException: Cannot invoke "Object.equals(Object)" because "<parameter1>" is null
```

`(make-hash-table)` and `:test 'equal` answer correctly; an explicit `:test 'eq` or
`'eql` throws. `nil` is Java `null` on the JVM backend, so the key equality the
explicit-test path emits must not call `equals` on the probe key. Bug-fix protocol: a
ci-spec case first (the four backends agree on every other key).
