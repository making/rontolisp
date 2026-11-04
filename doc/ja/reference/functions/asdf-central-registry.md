# asdf:\*central-registry\*

他のどの探索先よりも先に `NAME.asd` を探すディレクトリのリストです
([システム探索パス](../../guides/asdf-systems.md#システム探索パス))。本物の ASDF と同じく、各要素は
探索のたびに評価し、その値をパス名の指定子として扱います。したがって文字列、パス名、
`'*default-pathname-defaults*` のようなフォームを要素にできます。初期値は nil です。

```console
(push "vendor/my-lib/" asdf:*central-registry*)
(asdf:load-system :my-lib)
```

## バックエンドサポート

インタプリタは探索のたびに変数を読むので、計算した値の push や `let` による束縛も有効です。
コンパイルパスが読むのは、コンパイル時にわかるディレクトリのトップレベルの `push` と `pushnew`
だけです。`load-system` がリテラルの名前だけを読むのと同じです。変数自体は 4 つすべての
バックエンドで通常の変数です。
