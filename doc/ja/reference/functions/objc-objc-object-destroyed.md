# objc:objc-object-destroyed

`(objc:objc-object-destroyed object)`

`objc:standard-objc-object` の参照カウントが 0 になったとき、その `dealloc` の中で呼ばれる総称関数です。組み込みの主メソッドは何もせず、`:after` メソッドが `dealloc` の実装に当たります。それまで Lisp オブジェクトは生き続けます。`make-instance` の参照はプログラムが `objc:release` するものです。プログラムからは呼びません。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defmethod objc-object-destroyed :after ((object my-object))
          (format t "destroyed ~a~%" (slot-value object 'slot1)))
OBJC-OBJECT-DESTROYED
MY-APP> (release (make-instance 'my-object :slot1 :gone))
destroyed GONE
NIL
```
