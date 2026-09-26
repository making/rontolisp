# length

`(length sequence)`

シーケンスの要素数を返します。リスト・文字列・ランク 1 のベクタに対して動作し、`(length nil)` は `0` です。ランク 2 の配列はシーケンスではないため、それに対して `length` を呼ぶとエラーが発生します。その他のシーケンスでない値 (数値、シンボル、ハッシュテーブル) とドットリストは `type-error` を通知します。

```lisp
(length '(a b c d)) ; => 4
```

```lisp
(length "hello") ; => 5
```

```lisp
(handler-case (length 5) (type-error (e) (princ-to-string e))) ; => "LENGTH: The value 5 is not of type SEQUENCE"
```

```lisp
(handler-case (length '(1 2 . 3)) (type-error (e) (princ-to-string e))) ; => "LENGTH: The value 3 is not of type SEQUENCE"
```
