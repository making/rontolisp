# search

`(search sequence-1 sequence-2 &key start1 end1 start2 end2 test key from-end)`

`sequence-1` が `sequence-2` の部分シーケンスとして最初に現れる位置を返します。現れなければ nil です。`:from-end` を指定すると最後の出現の開始位置を返します。`:start1`/`:end1` はパターン側、`:start2`/`:end2` は検索対象側の範囲を限定し、要素は `:key` 適用後に `:test`(既定 `eql`)で比較されます。文字列・リスト・ベクタのいずれの組み合わせでも動作します。走査は単純な O(n*m) です。比較が既定の `eql` (あるいは `#'eql`、文字列同士なら `#'char=`) で `:key` を指定しない場合、インタプリタはこれをネイティブに実行し、それ以外では同じ移植版の走査に戻ります。

```lisp
(search "bc" "abcd") ; => 1
```

```lisp
(search "x" "abcd") ; => NIL
```

```lisp
(search "ab" "ab-ab" :from-end t) ; => 3
```
