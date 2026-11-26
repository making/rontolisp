# exit

`(exit)` `(exit obj)`

Ends the program. The `after` thunks of every `dynamic-wind` the call is inside run first, then output is flushed and the process exits. No argument or `#t` is status 0, `#f` is status 1, and an integer gives its low eight bits (`(exit 258)` is status 2).

```scheme
(display "working")
(newline)
(dynamic-wind
  (lambda () #f)
  (lambda () (exit 3))
  (lambda () (display "cleanup") (newline)))
(display "never printed")
```

```
working
cleanup
```
