(ns clojure.math
  "Functions over doubles -- trigonometry, exponentials and logarithms,
  rounding, the neighbours of a double -- and long arithmetic that refuses to
  overflow, built into rontolisp. Written for this front end from the
  documented behaviour of Clojure's namespace of the same name. The double
  functions answer java.lang.StrictMath's bits, the same on every backend;
  Clojure's call java.lang.Math, whose answers may differ in the last place."
  (:require [rontolisp.internal.math :as k]))

(def E
  "The double nearest e, the base of the natural logarithm."
  2.718281828459045)

(def PI
  "The double nearest pi, the circumference of a circle over its diameter."
  3.141592653589793)

(defn sin
  "The sine of the angle a, in radians."
  [a]
  (k/sin a))

(defn cos
  "The cosine of the angle a, in radians."
  [a]
  (k/cos a))

(defn tan
  "The tangent of the angle a, in radians."
  [a]
  (k/tan a))

(defn asin
  "The angle in [-pi/2, pi/2] whose sine is a; ##NaN when a is outside [-1, 1]."
  [a]
  (k/asin a))

(defn acos
  "The angle in [0, pi] whose cosine is a; ##NaN when a is outside [-1, 1]."
  [a]
  (k/acos a))

(defn atan
  "The angle in [-pi/2, pi/2] whose tangent is a."
  [a]
  (k/atan a))

(defn to-radians
  "The angle deg, in degrees, in radians."
  [deg]
  (* (double deg) 0.017453292519943295))

(defn to-degrees
  "The angle r, in radians, in degrees."
  [r]
  (* (double r) 57.29577951308232))

(defn exp
  "e raised to the power a."
  [a]
  (k/exp a))

(defn log
  "The natural logarithm of a: ##-Inf at zero, ##NaN below it."
  [a]
  (k/log a))

(defn log10
  "The base 10 logarithm of a: ##-Inf at zero, ##NaN below it."
  [a]
  (k/log10 a))

(defn sqrt
  "The positive square root of a, correctly rounded; ##NaN below zero."
  [a]
  (k/sqrt a))

(defn cbrt
  "The cube root of a."
  [a]
  (k/cbrt a))

(defn IEEE-remainder
  "dividend minus n times divisor, n the integer nearest the quotient
  dividend / divisor (the even one when two are as near)."
  [dividend divisor]
  (k/IEEE-remainder dividend divisor))

(defn ceil
  "The least integral double not below a; a zero keeps the sign of a."
  [a]
  (k/ceil a))

(defn floor
  "The greatest integral double not above a; a zero keeps the sign of a."
  [a]
  (k/floor a))

(defn rint
  "The integral double nearest a, the even one when two are as near."
  [a]
  (k/rint a))

(defn atan2
  "The angle from the positive x axis to the point (x, y), in [-pi, pi]."
  [y x]
  (k/atan2 y x))

(defn pow
  "a raised to the power b."
  [a b]
  (k/pow a b))

(defn round
  "The long nearest a, rounding up when two are as near; 0 for ##NaN, and the
  long range's nearest end for a double past it."
  [a]
  (k/round a))

(defn random
  "A double drawn uniformly from [0.0, 1.0)."
  []
  (rand))

(defn add-exact
  "The sum of the longs x and y; an ArithmeticException past the long range."
  [x y]
  (k/add-exact x y))

(defn subtract-exact
  "The difference of the longs x and y; an ArithmeticException past the long
  range."
  [x y]
  (k/subtract-exact x y))

(defn multiply-exact
  "The product of the longs x and y; an ArithmeticException past the long
  range."
  [x y]
  (k/multiply-exact x y))

(defn increment-exact
  "The long a plus one; an ArithmeticException past the long range."
  [a]
  (k/increment-exact a))

(defn decrement-exact
  "The long a minus one; an ArithmeticException past the long range."
  [a]
  (k/decrement-exact a))

(defn negate-exact
  "Minus the long a; an ArithmeticException for the least long, which has no
  long negation."
  [a]
  (k/negate-exact a))

(defn floor-div
  "The greatest long not above x / y, for the longs x and y; an
  ArithmeticException when y is zero."
  [x y]
  (k/floor-div x y))

(defn floor-mod
  "x minus (floor-div x y) times y, which has the sign of y; an
  ArithmeticException when y is zero."
  [x y]
  (k/floor-mod x y))

(defn ulp
  "The distance from d to the next double of greater magnitude."
  [d]
  (k/ulp d))

(defn signum
  "1.0 for a positive d, -1.0 for a negative one; a zero or ##NaN itself."
  [d]
  (k/signum d))

(defn sinh
  "The hyperbolic sine of x."
  [x]
  (k/sinh x))

(defn cosh
  "The hyperbolic cosine of x."
  [x]
  (k/cosh x))

(defn tanh
  "The hyperbolic tangent of x."
  [x]
  (k/tanh x))

(defn hypot
  "The square root of x^2 + y^2, with no overflow or underflow on the way."
  [x y]
  (k/hypot x y))

(defn expm1
  "e^x - 1, accurate where x is near zero."
  [x]
  (k/expm1 x))

(defn log1p
  "The natural logarithm of 1 + x, accurate where x is near zero."
  [x]
  (k/log1p x))

(defn copy-sign
  "magnitude with the sign of sign; a ##NaN sign counts as positive."
  [magnitude sign]
  (k/copy-sign magnitude sign))

(defn get-exponent
  "The unbiased binary exponent of d: 1024 for an infinity or ##NaN, -1023 for
  a zero or a subnormal."
  [d]
  (k/get-exponent d))

(defn next-after
  "The double next to start in the direction of direction; direction itself
  when the two are equal."
  [start direction]
  (k/next-after start direction))

(defn next-up
  "The double next to d toward ##Inf."
  [d]
  (k/next-up d))

(defn next-down
  "The double next to d toward ##-Inf."
  [d]
  (k/next-down d))

(defn scalb
  "d times 2 to the power scaleFactor, rounded once."
  [d scaleFactor]
  (k/scalb d scaleFactor))
