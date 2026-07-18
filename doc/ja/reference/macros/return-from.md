# return-from

`(return-from name [value])`

`name` という名前の囲んでいるブロックから `value`(デフォルト `nil`)を返します。インタープリタでは本物の名前付き非局所脱出です: `defun` 本体は関数名のブロック、`defmethod` 本体はジェネリック名のブロックになるため、`do`/`loop`(その暗黙ブロックの名前は nil)の内側からでも、脱出のダイナミックエクステント内で呼ばれたクロージャの中からでも、`return-from` は関数から抜けます。`(return-from nil v)` は `(return v)` と同じです。コンパイルパスの lite 逸脱: コンパイラは名前を落として `(return value)` に書き換えるため、`do`/`loop` 内にネストした `return-from` は関数ではなくそのループ(最も近いブロック)から抜けます — ループが関数の最後のフォームである場合にのみ等価です。

```lisp
(defun classify (n)
  (when (= n 0)
    (return-from classify :zero))
  (* n 10))
(classify 0) ; => :zero
```

```lisp
(defun first-even (items)
  (dolist (x items)
    (when (evenp x)
      (return-from first-even x)))
  :none)
(first-even '(1 3 4 5)) ; => 4
```
