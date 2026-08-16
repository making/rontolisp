# return-from

`(return-from name [value])`

`name` という名前の囲んでいるブロックから `value`(デフォルト `nil`)を返します。`defun` 本体は関数名のブロック、`defmethod` 本体はジェネリック名のブロックになるため、`do`/`loop`(その暗黙ブロックの名前は nil)の内側からでも `return-from` は関数から抜けます。`(return-from nil v)` は `(return v)` と同じです。同じ関数内の `return-from` は直接ジャンプにコンパイルされます。囲んでいるブロックを名指しする lambda 内の `return-from`(たとえば `mapcar`/`mapl` に渡した lambda の中)は、全バックエンドでそのブロックから非局所脱出し、Common Lisp と一致します。(このような lambda 境界をまたぐ脱出は例外処理モードでコンパイルされるため、WASM バックエンドでは `wasmtime -W exceptions=y` が必要です。lambda をまたがない `return-from` はフラグ不要のままです。`flet`/`labels` のローカル関数をまたぐ必要がある `return-from` はコンパイラではまだ未対応です。)

```lisp
(defun classify (n)
  (when (= n 0)
    (return-from classify :zero))
  (* n 10))
(classify 0) ; => :ZERO
```

```lisp
(defun first-even (items)
  (dolist (x items)
    (when (evenp x)
      (return-from first-even x)))
  :none)
(first-even '(1 3 4 5)) ; => 4
```
