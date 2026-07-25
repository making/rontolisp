# hash-table-test

`(hash-table-test hash-table)`

Returns the test the table's lookups implement -- always the symbol `equal`. rontolisp keys every table structurally on every backend, whatever `:test` was passed to [`make-hash-table`](make-hash-table.md), so reporting the requested test would describe behavior that does not exist here.

```lisp
(hash-table-test (make-hash-table)) ; => EQUAL
```
