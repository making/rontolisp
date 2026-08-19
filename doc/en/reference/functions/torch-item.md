# torch:item

`(torch:item tensor)`

Returns the single element of a scalar (or one-element) tensor as a number -- the way a loss value leaves the graph for printing or logging. A tensor with more than one element signals.

```lisp
(torch:item (torch:sum (torch:tensor '(1.0 2.0 3.0)))) ; => 6.0
(torch:item (torch:tensor '(7.0)))                      ; => 7.0
```
