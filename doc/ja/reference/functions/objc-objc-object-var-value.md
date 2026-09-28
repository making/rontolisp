# objc:objc-object-var-value

`(objc:objc-object-var-value object var-name &key result-pointer)`

`objc:define-objc-class` の `(:objc-instance-vars ...)` で宣言したインスタンス変数 `var-name` の値を、その型で変換して返します。`setf` で書き込めます (オブジェクト型の変数はオブジェクトポインタを受け取り、retain はしません)。構造体の値は、`result-pointer` を渡せばそこに埋めます。その変数を持たないオブジェクトではシグナルします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-class counter () () (:objc-class-name "Counter")
          (:objc-instance-vars ("count" :int)))
COUNTER
MY-APP> (defvar *c* (make-instance 'counter))
*C*
MY-APP> (setf (objc-object-var-value *c* "count") 17)
17
MY-APP> (objc-object-var-value *c* "count")
17
```
