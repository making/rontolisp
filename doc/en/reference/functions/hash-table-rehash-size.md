# hash-table-rehash-size

`(hash-table-rehash-size hash-table)`

Returns the standard default growth factor `1.5`. rontolisp tables do not expose a growth knob (the host map grows on its own), so the value is a constant reported for the benefit of portable code that reads it before rebuilding a table.

```lisp
(hash-table-rehash-size (make-hash-table)) ; => 1.5
```
