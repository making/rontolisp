# block

`(block name body...)`

`body` の周りに名前付きブロックを確立し、最後のフォームの値、または本体実行中に発火したマッチする `(return-from name value)` の値を返します。インタープリタでは名前は本物です: 外側のブロックを狙う内側の `return-from` は、間にあるブロックやループを素通りします。また `(block nil ...)` はループマクロの暗黙 nil ブロックと同様、プレーンな `(return ...)` も受け取ります。コンパイルパスの lite 逸脱: コンパイラは名前を落とす(本体は内部 `%block` としてコンパイルされる)ため、そこでは `return-from` は最も近い囲みブロックを狙います。

```lisp
(block scan
  (dotimes (i 10)
    (when (= i 4) (return-from scan (* i 100))))
  :fell-through) ; => 400
```

```lisp
(block nil (return 7) 9) ; => 7
```
