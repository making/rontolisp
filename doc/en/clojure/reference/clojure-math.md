# clojure.math

Functions over doubles and long arithmetic that refuses to overflow. Require `clojure.math` to
use it; it is Clojure source written for rontolisp from the documented behavior of Clojure's
namespace. The double functions answer `java.lang.StrictMath`'s bits on the interpreter, the
JVM and both WASM targets, so a program prints the same digits on every backend (`Math/sin`
is Java interop, which no WASM backend has).

| Var | Behavior |
|---|---|
| `E`, `PI` | The doubles nearest e and pi |
| `sin`, `cos`, `tan` | `(sin a)`: the sine of the angle `a`, in radians |
| `asin`, `acos`, `atan` | `(asin a)`: the angle whose sine is `a`; `##NaN` when `a` is outside [-1, 1] |
| `atan2` | `(atan2 y x)`: the angle from the positive x axis to the point (x, y), in [-pi, pi] |
| `sinh`, `cosh`, `tanh` | The hyperbolic functions |
| `exp`, `expm1` | `(exp a)`: e raised to `a`; `(expm1 x)`: e^x - 1, accurate near zero |
| `log`, `log10`, `log1p` | The natural and base 10 logarithms, `##-Inf` at zero and `##NaN` below it; `(log1p x)`: the logarithm of 1 + x, accurate near zero |
| `pow`, `sqrt`, `cbrt`, `hypot` | `(pow a b)`: `a` raised to `b`, `##NaN` for a negative `a` and a non-integral `b`; `(hypot x y)`: sqrt(x^2 + y^2) with no intermediate overflow |
| `to-radians`, `to-degrees` | Converts an angle between degrees and radians |
| `ceil`, `floor`, `rint` | The integral double above, below or nearest (`rint` takes the even one at a tie); a zero answer keeps the argument's sign |
| `round` | `(round a)`: the long nearest `a`, a tie rounding up; `0` for `##NaN`, and the nearest end of the long range for a double past it |
| `IEEE-remainder` | `(IEEE-remainder dividend divisor)`: the dividend minus `n` divisors, `n` the integer nearest the quotient |
| `signum`, `copy-sign` | `(signum d)`: `1.0` or `-1.0` by the sign of `d`, a zero or `##NaN` itself; `(copy-sign magnitude sign)` |
| `ulp`, `get-exponent` | `(ulp d)`: the distance to the next double away from zero; `(get-exponent d)`: the unbiased binary exponent |
| `next-after`, `next-up`, `next-down` | The neighboring double toward a direction, `##Inf` or `##-Inf` |
| `scalb` | `(scalb d n)`: `d` times 2^n, rounded once |
| `add-exact`, `subtract-exact`, `multiply-exact` | `(add-exact x y)`: the long sum; an `ArithmeticException` (`long overflow`) past the long range |
| `increment-exact`, `decrement-exact`, `negate-exact` | The long plus one, minus one, negated, refused like `add-exact` |
| `floor-div`, `floor-mod` | `(floor-div x y)`: the greatest long not above x / y; `(floor-mod x y)`: the remainder, with y's sign; an `ArithmeticException` (`/ by zero`) for a zero `y` |
| `random` | A double drawn uniformly from [0.0, 1.0) |

A double function converts each argument as `double` does: a long, a ratio and a decimal are
numbers, `nil` is a `NullPointerException` and anything else a `ClassCastException`. A long
function truncates a double or a ratio, takes a character as its code, answers 0 for `##NaN` and
refuses a value past the long range with an `IllegalArgumentException`; `scalb`'s exponent
also refuses one past the int range, with an `ArithmeticException` (`integer overflow`).

```clojure
(require '[clojure.math :as m])
(m/sqrt 2)
; => 1.4142135623730951
(m/pow 2 10)
; => 1024.0
(m/log -1.0)
; => ##NaN
(m/ceil -0.5)
; => -0.0
(m/round 2.5)
; => 3
(m/floor-div -7 2)
; => -4
(m/scalb 1.0 -1074)
; => 4.9E-324
```

```clojure
(require '[clojure.math :as m])
(try (m/add-exact 9223372036854775807 1) (catch ArithmeticException e (ex-message e)))
; => "long overflow"
(map m/next-up [1.0 -0.0])
; => (1.0000000000000002 4.9E-324)
```

## Differences

- The double functions answer `java.lang.StrictMath`'s bits; Clojure's call `java.lang.Math`,
  whose x86-64 implementations of `sin`, `cos`, `tan`, `exp`, `log`, `log10`, `cbrt`, `tanh`
  and `pow` differ in the last place on 1 to 8 percent of arguments: `(m/exp 1)` is
  `2.7182818284590455` here and `2.718281828459045` in Clojure. The other functions answer
  alike.
- `copy-sign` reads a `##NaN` sign as positive, where Clojure's follows the sign bit a NaN
  happens to carry.
- A ratio converts to its nearest double, where Clojure rounds it to 16 significant digits
  first: `(m/sin 2/3)` takes `0.6666666666666666` here and `0.6666666666666667` in Clojure.
- `scalb` casts a double exponent as Clojure casts one held in a local: past the int range it
  is an `ArithmeticException`, where Clojure's literal double exponent is an
  `IllegalArgumentException`. A character is a long's code in a function value too (`(map
  m/floor-div [\a] [2])`), where Clojure's function value refuses it.
