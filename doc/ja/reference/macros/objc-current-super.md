# objc:current-super

`(objc:current-super)`

`objc:define-objc-method` や `objc:define-objc-class-method` の本体の中で、`objc:invoke`、`objc:invoke-bool`、`objc:invoke-into`、`objc:can-invoke-p` がメソッドを定義したクラスのスーパークラスに送る値になります (Objective-C の `super`)。本体の外では未束縛の変数です。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-class my-special-object (my-object)
          ()
          (:objc-class-name "MySpecialObject"))
MY-SPECIAL-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-special-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* 4 (invoke (current-super) "areaOfWidth:height:" width height)))
"areaOfWidth:height:"
MY-APP> (invoke (alloc-init-object "MySpecialObject") "areaOfWidth:height:" 6 7)
168
```
