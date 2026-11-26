# objc:objc-object-copied

`(objc:objc-object-copied old-object new-object)`

`objc:standard-objc-object` が `copyWithZone:` (`copy`) で複製されたとき、複製の Lisp オブジェクトとともに呼ばれる総称関数です。組み込みの主メソッドがスロットを複写し、`:after` メソッドが `copyWithZone:` の実装に当たります。プログラムからは呼びません。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defmethod objc-object-copied :after ((old my-object) (new my-object))
          (format t "copied~%"))
OBJC-OBJECT-COPIED
MY-APP> (slot-value (objc-object-from-pointer (invoke (make-instance 'my-object :slot1 :a) "copy")) 'slot1)
copied
:A
```
