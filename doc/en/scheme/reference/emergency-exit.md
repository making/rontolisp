# emergency-exit

`(emergency-exit)` `(emergency-exit obj)`

Ends the program where the call stands, without running the `after` thunks of any enclosing `dynamic-wind`. Output written so far is flushed. The status is mapped as for `exit`: none or `#t` is 0, `#f` is 1, an integer its low eight bits.

```scheme
(display "working")
(newline)
(dynamic-wind
  (lambda () #f)
  (lambda () (emergency-exit 4))
  (lambda () (display "cleanup") (newline)))
```

```
working
```
