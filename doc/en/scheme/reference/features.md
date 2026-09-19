# features

`(features)`

Returns a fresh list of the feature identifiers a [cond-expand](cond-expand.md) tests: `r7rs`, `exact-closed` (exact arithmetic other than `/` stays exact), `ieee-float` (inexact numbers are IEEE 754 doubles), `full-unicode` (a character per Unicode code point), `ratios` (`/` of exact numbers is exact) and `rontolisp`. Each holds on every backend alike, so the list names no operating system or processor. Gauche answers a longer list of its own.

```scheme
(features) ; => (r7rs exact-closed ieee-float full-unicode ratios rontolisp)
(if (memq 'ratios (features)) 'exact 'inexact) ; => exact
```
