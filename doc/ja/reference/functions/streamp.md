# streamp

`(streamp object)`

`object` がストリームであれば `t` を、そうでなければ `nil` を返します。ストリームはすべてのバックエンドで不透明な整数ハンドルなので、これは `integerp` に相当する軽量な判定です。標準出力の指定子 `t` (`*standard-output*` の束縛値) もストリームとして扱われます。`check-type`/`typecase` が使う `stream` 型指定子も同じ判定に基づいています。その部分型名も解決します: `synonym-stream` は正確な判定を持ちます(シノニムストリームはハンドルではなく値である唯一のストリーム種別です)。一方 `file-stream` は `streamp` と同じ方向にライトで、あらゆるハンドルストリームに対して真になります。ハンドルにはファイルか文字列ストリームかを区別する情報が無いためです。`readtable` は `*readtable*` が保持する不透明な `nil` トークンの型です。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(with-output-to-string (s) (princ (streamp s) s)) ; => "T"
```
