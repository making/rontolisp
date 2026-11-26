# reverse

`(reverse sequence)`

Returns a new sequence with the elements of `sequence` in reverse order, leaving the original untouched. The sequence may be a list or a string; a string reverses to a new string. This is the non-destructive counterpart to `nreverse`. The empty list reverses to `nil`; any other non-sequence, or a dotted list, signals a `type-error`.

```lisp
(reverse '(1 2 3)) ; => (3 2 1)
```

```lisp
(reverse "abc") ; => "cba"
```

```lisp
(handler-case (reverse 5) (type-error (e) (princ-to-string e))) ; => "REVERSE: The value 5 is not of type SEQUENCE"
```
