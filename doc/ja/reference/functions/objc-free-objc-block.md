# objc:free-objc-block

`(objc:free-objc-block block)`

`objc:make-objc-block` で作ったブロックの領域を解放し、プログラムがその関数に対して持つ保持を手放します。`nil` を返し、解放済みのブロックを再び解放しても何もしません。ブロックを保持した呼び出し先は自分のコピーを持つため、関数は最後のコピーが解放されるまで生きています。解放済みの `objc:objc-block` 自体は、渡したり呼んだりするとシグナルします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (free-objc-block *add*)
NIL
MY-APP> *add*
#<OBJC:OBJC-BLOCK i@?ii freed>
```
