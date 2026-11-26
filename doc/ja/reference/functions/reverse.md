# reverse

`(reverse sequence)`

`sequence` の要素を逆順に並べた新しいシーケンスを返します。元のシーケンスは変更されません。シーケンスにはリストまたは文字列を渡せます。文字列は新しい文字列として逆順になります。`nreverse` の非破壊版です。空のリストは `nil` になります。その他のシーケンスでない値やドットリストは `type-error` を通知します。

```lisp
(reverse '(1 2 3)) ; => (3 2 1)
```

```lisp
(reverse "abc") ; => "cba"
```

```lisp
(handler-case (reverse 5) (type-error (e) (princ-to-string e))) ; => "REVERSE: The value 5 is not of type SEQUENCE"
```
