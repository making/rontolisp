# lower-case-p, upper-case-p

`(lower-case-p character)`
`(upper-case-p character)`

`lower-case-p` returns `t` if `character` is a lowercase letter, `upper-case-p` if it is an uppercase letter, and `nil` otherwise. A character counts as lowercase exactly when upcasing it changes it (and uppercase when downcasing changes it), so both follow the platform's Unicode case tables. Available on all backends except `--no-gc`.

```lisp
(list (lower-case-p #\a) (upper-case-p #\A) (lower-case-p #\5)) ; => (t t nil)
```
