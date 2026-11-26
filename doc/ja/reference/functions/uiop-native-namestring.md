# uiop:native-namestring

`(uiop:native-namestring pathname)`

パス名の名前文字列を、ホスト OS 自身の綴りで返します。rontolisp の
名前文字列はもともとホストの綴りそのもので、Lisp 構文とネイティブ構文を
変換するバックエンドはないため、これは `namestring` と同じです。パスが
Lisp の外へ出る場面でライブラリが呼びます (jzon はパス名値をこれで
文字列化し、trivial-mimes は外部プローブへ渡します)。

```lisp
(uiop:native-namestring #P"/tmp/data.json")   ; => "/tmp/data.json"
```

## バックエンド対応

全 4 バックエンド: インタープリタでは組み込み、コンパイル経路では
`namestring` に低下されます。
