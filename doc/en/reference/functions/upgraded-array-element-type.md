# upgraded-array-element-type

`(upgraded-array-element-type typespec &optional environment)`

The element type of an array made with `typespec` as its `:element-type`, which [`array-element-type`](array-element-type.md) answers: `t` for a type no specialized array holds. `environment` is ignored.

```lisp
(list (upgraded-array-element-type 'character)
      (upgraded-array-element-type 'bit)
      (upgraded-array-element-type 't)) ; => (CHARACTER BIT T)
```
