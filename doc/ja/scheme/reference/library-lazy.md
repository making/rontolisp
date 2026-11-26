# (scheme lazy)

プロミスです。`delay` と `delay-force` の構文と、その値を扱う手続きです。

| 名前 | 例 | 結果 |
|---|---|---|
| `delay` | `(force (delay (* 6 7)))` | `42` |
| `delay-force` | `(force (delay-force (delay (+ 1 2))))` | `3` |
| `force` | `(force (delay (+ 1 2)))` | `3` |
| `make-promise` | `(force (make-promise 42))` | `42` |
| `promise?` | `(promise? (delay 1))` | `#t` |
