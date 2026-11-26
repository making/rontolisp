# hash-table-test

`(hash-table-test hash-table)`

Returns the test the table's lookups implement: `equalp` for a table made with `:test 'equalp`, whose keys are folded to a case- and float-insensitive representative before they are placed, `eql` and `eq` for tables made with those tests, whose aggregates key by identity, and `equal` for every other one.

```lisp
(list (hash-table-test (make-hash-table))
      (hash-table-test (make-hash-table :test 'equalp))) ; => (EQUAL EQUALP)
```
