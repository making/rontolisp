# block

`(block name body...)`

`body` の周りに名前付きブロックを確立し、最後のフォームの値、または本体実行中に発火したマッチする `(return-from name value)` の値を返します。名前のマッチングはすべてのバックエンドで行われます: 外側のブロックを狙う内側の `return-from` は、間にあるブロックやループを素通りします。また `(block nil ...)` はループマクロの暗黙 nil ブロックと同様、プレーンな `(return ...)` も受け取ります。インタープリタではマッチは動的(ブロックのエクステント内のクロージャ呼び出しも越える)で、コンパイラは同じ関数内でレキシカルにマッチします。

```lisp
(block scan
  (dotimes (i 10)
    (when (= i 4) (return-from scan (* i 100))))
  :fell-through) ; => 400
```

```lisp
(block nil (return 7) 9) ; => 7
```
