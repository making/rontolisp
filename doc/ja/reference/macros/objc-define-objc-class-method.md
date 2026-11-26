# objc:define-objc-class-method

`(objc:define-objc-class-method (name result-type [result-style]) ((object-var class-name [pointer-var]) (arg-var arg-type [arg-style])*) form*)`

`objc:define-objc-class` で定義したクラスのクラスメソッド `name` を、`objc:define-objc-method` がインスタンスメソッドを定義するのと同じように定義します。`object-var` には Lisp のクラス、`pointer-var` には Objective-C のクラスが束縛され、`(objc:current-super)` はスーパークラスのクラスメソッドに送ります。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。定義にランタイムは要りません。Objective-C 側はランタイムを開いたとき (`objc:ensure-objc-initialized` か最初の送信) に作られます。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-class-method ("describeClass" objc-object-pointer) ((class my-object))
          (concatenate 'string "class " (invoke-into 'string (current-super) "description")))
"describeClass"
MY-APP> (invoke-into 'string "MyObject" "describeClass")
"class MyObject"
```
