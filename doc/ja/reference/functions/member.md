# member

`(member item list &key test key)`

`item` に一致する最初の要素を `list` から探し、その要素から始まる部分リスト（末尾）を返します。一致するものがなければ `nil` を返します。デフォルトの比較は `eql` です。オプションの `:test` キーワードに関数指定子を渡すと別の比較を使え、オプションの `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます。結果はコピーではなく、元のリストと構造を共有します。リストでない `list` や、探索が末尾まで達したドットリストは `type-error` を通知します。

```lisp
(member 2 '(1 2 3)) ; => (2 3)
```

```lisp
(member '(a d) '((a b) (a d)) :test 'equal) ; => ((A D))
```

```lisp
(member 3 '((1 2) (3 4) (5 6)) :key #'car) ; => ((3 4) (5 6))
```

```lisp
(handler-case (member 1 5) (type-error (e) (princ-to-string e))) ; => "MEMBER: The value 5 is not of type LIST"
```
