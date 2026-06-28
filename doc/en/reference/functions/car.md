# car

`(car list)`

Returns the car (first element) of a cons cell. As a special case `(car nil)` is `nil` rather than an error, so traversing to the end of a list is safe. Use `first` as a more readable synonym when working with lists.

```lisp
(car '(1 2 3)) ; => 1
```

```lisp
(car nil) ; => nil
```
