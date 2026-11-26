# (scheme complex)

The complex half of the numeric tower: the rectangular and polar constructors and the accessors. A complex prints as `#C(real imag)` and a literal reads back the same way; a literal whose imaginary part is the exact zero is the real itself (`#C(1 0)` is `1`). Gauche prints `1.0+2.0i` and answers `1.0` for `(make-rectangular 1 0)` -- a deviation, recorded in the deviations page.

| Name | Example | Result |
|---|---|---|
| `make-rectangular` | `(make-rectangular 1 2)` | `#C(1 2)` |
| `make-polar` | `(make-polar 1 0.7853981633974483)` | `#C(0.7071067811865476 0.7071067811865475)` |
| `real-part` | `(real-part #C(1 2))` | `1` |
| `imag-part` | `(imag-part #C(1 2))` | `2` |
| `magnitude` | `(magnitude #C(3 4))` | `5.0` |
| `angle` | `(angle #C(1 1))` | `0.7853981633974483` |
