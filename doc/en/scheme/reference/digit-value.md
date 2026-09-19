# digit-value

`(digit-value char)`

Returns the value of `char` as a decimal digit (0 to 9) if it is one in any script -- exactly when `char-numeric?` answers `#t` -- otherwise `#f`.

```scheme
(digit-value #\3) ; => 3
(digit-value #\٤) ; => 4
(digit-value #\x) ; => #f
```
