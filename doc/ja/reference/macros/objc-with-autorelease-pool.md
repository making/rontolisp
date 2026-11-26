# objc:with-autorelease-pool

`(objc:with-autorelease-pool (option*) form*)`

新しい自動解放プールのもとで `form` を評価し、非局所脱出を含むすべての脱出でプールを空にして、最後のフォームの値を返します。`option` は空でなければなりません。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defun object-description (object)
           (objc:with-autorelease-pool ()
             (objc:invoke-into 'string object "description")))
OBJECT-DESCRIPTION
CL-USER> (object-description (objc:invoke "NSNumber" "numberWithInt:" 42))
"42"
```
