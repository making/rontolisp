# count

`(count item sequence &key test key start end from-end)`

`sequence` の要素のうち `item` に一致するものの個数を返します。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。述語で数えるには `count-if` を使います。`:start`/`:end` は走査する部分列を区切り、`:from-end` は要素を訪れる順序を逆にします。個数自体は変わりませんが、副作用のある `:key` や `:test` は逆順で呼ばれます。nil の `:end` は長さを意味し、nil の `:start` は `type-error` を通知します。

```lisp
(count 2 '(1 2 3 2 2)) ; => 3
```

```lisp
(count #\a "banana") ; => 3
```

```lisp
(count "a" '("a" "b" "a") :test #'string=) ; => 2
```

```lisp
(count 1 '(1 1 2 1) :start 1) ; => 2
```
