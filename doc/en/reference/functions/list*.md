# list*

`(list* object &rest more)`

Conses the leading arguments onto the last argument, which becomes the tail. When the final argument is a list the result is a proper list extended at the front; when it is a non-list the result is a dotted list. Calling it with a single argument just returns that argument.

```lisp
(list* 1 2 '(3 4)) ; => (1 2 3 4)
```

```lisp
(list* 1 2 3) ; => (1 2 . 3)
```
