# list

`(list &rest objects)`

Returns a freshly allocated proper list of its evaluated arguments, in order. With no arguments it returns `nil` (the empty list). Each argument becomes one element, so nested calls build nested lists.

```lisp
(list 1 2 3) ; => (1 2 3)
```

```lisp
(list) ; => nil
```
