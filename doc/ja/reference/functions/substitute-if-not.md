# substitute-if-not

`(substitute-if-not new predicate sequence &key key)`

[`substitute-if`](substitute-if.md) の補集合版です。述語が*満たさない*と判定したすべての要素を `new` に置き換えた新しいシーケンスを返します。同じ省略可能な `:key` セレクタを取り、シーケンスの種類を保ち、元のシーケンスは変更しません。破壊的な版は [`nsubstitute-if-not`](nsubstitute-if-not.md) です。

```lisp
(substitute-if-not 0 #'oddp '(1 2 3 4 5)) ; => (1 0 3 0 5)
```

```lisp
(substitute-if-not 'keep #'stringp '("a" 1 "b")) ; => ("a" KEEP "b")
```
