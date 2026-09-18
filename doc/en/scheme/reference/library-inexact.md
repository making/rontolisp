# (scheme inexact)

Transcendental functions and the float classification predicates. There are no complex numbers: an argument whose result would be complex is an error naming the procedure.

| Name | Example | Result |
|---|---|---|
| `sqrt` | `(sqrt 16)` | `4` |
| `exp` | `(exp 0)` | `1` |
| `log` | `(log 1)` | `0` |
| `sin` | `(sin 0)` | `0` |
| `cos` | `(cos 0)` | `1` |
| `tan` | `(tan 0)` | `0` |
| `asin` | `(asin 1)` | `1.5707963267948966` |
| `acos` | `(acos 0)` | `1.5707963267948966` |
| `atan` | `(atan 1)` | `0.7853981633974483` |
| `finite?` | `(finite? 1.5)` | `#t` |
| `infinite?` | `(infinite? (/ -1.0 0.0))` | `#t` |
| `nan?` | `(nan? (/ 0.0 0.0))` | `#t` |
