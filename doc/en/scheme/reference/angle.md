# angle

`(angle z)`

Returns the angle of the complex `z` in radians, the atan of the imaginary part over the real part. The angle of a positive real is `0.0`, of a negative real pi.

```scheme
(angle #C(1 1)) ; => 0.7853981633974483
(angle 2) ; => 0.0
(angle -1.0) ; => 3.141592653589793
```
