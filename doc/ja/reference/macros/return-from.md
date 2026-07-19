# return-from

`(return-from name [value])`

`name` という名前の囲んでいるブロックから `value`(デフォルト `nil`)を返します。`defun` 本体は関数名のブロック、`defmethod` 本体はジェネリック名のブロックになるため、`do`/`loop`(その暗黙ブロックの名前は nil)の内側からでも `return-from` は関数から抜けます。`(return-from nil v)` は `(return v)` と同じです。インタープリタでは脱出は動的(名前付きシグナル)なので、脱出のダイナミックエクステント内で呼ばれたクロージャの中からも抜けられます。コンパイラは脱出をレキシカルに実装します: ターゲットは同じ関数内のレキシカルに囲むブロックでなければならず、名前がどの囲みブロックにもマッチしない lambda 内の `return-from` は外側の関数ではなくその lambda から抜けます。

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
