# get-validator

`(get-validator reference)`

atom、ref、agent、var の validator（`set-validator!`、または `atom`・`ref`・`agent` の `:validator` オプション）を返し、なければ `nil` を返します。関数値として動きます。

```clojure
(println (get-validator (atom 1)) (fn? (get-validator (atom 1 :validator pos?))))
```

```
nil true
```
