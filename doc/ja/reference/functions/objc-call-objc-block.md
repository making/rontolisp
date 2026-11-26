# objc:call-objc-block

`(objc:call-objc-block type block &rest args)`

ブロック (`objc:make-objc-block` で作ったもの、メソッドが返したブロックオブジェクト、またはアドレス) を呼び出し元のスレッドで呼びます。`args` は `type` (`objc:make-objc-block` と同じ指定子) で変換され、結果は逆向きに変換されます。ブロックは自分のシグネチャを確実には持っていないため、呼ぶ側が型を示します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (call-objc-block '(:int (:int :int)) *add* 3 4)
7
```
