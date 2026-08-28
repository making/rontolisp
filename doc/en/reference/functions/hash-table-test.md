# hash-table-test

`(hash-table-test hash-table)`

Returns the test the table's lookups implement: `equalp` for a table made with `:test 'equalp`, whose keys are folded to a case- and float-insensitive representative before they are placed, and `equal` for every other one. An `eql` table keys structurally like an `equal` one on every backend, so reporting the requested test would describe behavior that does not exist here.

```lisp
(list (hash-table-test (make-hash-table))
      (hash-table-test (make-hash-table :test 'equalp))) ; => (EQUAL EQUALP)
```
