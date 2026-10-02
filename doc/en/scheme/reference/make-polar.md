# make-polar

`(make-polar magnitude angle)`

Returns the complex `magnitude` at `angle` radians, `magnitude*cos(angle) + magnitude*sin(angle)*i`.

```scheme
(make-polar 1 0.7853981633974483) ; => #C(0.7071067811865476 0.7071067811865475)
```
