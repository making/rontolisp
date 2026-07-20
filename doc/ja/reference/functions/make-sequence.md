# make-sequence

`(make-sequence result-type size &key initial-element)`

指定した型とサイズのシーケンスを作成します。結果型はリテラルのクォートされた指定子でなければなりません。文字列型 (`string`、`simple-string`、`base-string`、`simple-base-string`) は [`make-string`](make-string.md) と同様に文字列を、`list` は [`make-list`](make-list.md) と同様にリストを、ベクタ型 (`vector`、`simple-vector`) は [`make-array`](make-array.md) と同様に配列を作成します。リテラルでない結果型はエラーです。キーワード引数は下位のコンストラクタにそのまま渡されます (`:initial-element` の対応は渡し先に従います)。

```lisp
(length (make-sequence 'simple-string 5)) ; => 5
```

```lisp
(make-sequence 'list 3) ; => (NIL NIL NIL)
```
