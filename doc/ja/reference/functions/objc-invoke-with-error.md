# objc:invoke-with-error

`(objc:invoke-with-error class-or-object-pointer method &rest args)`

`method` (文字列、または `objc:invoke` が受け付けるリスト形式。名前は `error:` または `Error:` で終わる。例: `startAndReturnError:`) を、`args` の後に自分で用意した `NSError **` を加えて送ります。結果が失敗 (`nil`、`NO` または 0) を示し、メソッドがエラーを書き込んだ場合は、その `NSError` を保持する `objc:ns-error` をシグナルします。それ以外の場合は `objc:invoke` と同じ値を返します。名前がどちらでも終わらないメソッドは拒否します。`NSError` の補助がない LispWorks にはない関数です。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                         "attributesOfItemAtPath:error:" "/no/such/file")
          (ns-error (e) (list (ns-error-domain e) (ns-error-code e))))
("NSCocoaErrorDomain" 260)
MY-APP> (null (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                 "attributesOfItemAtPath:error:" "/"))
NIL
```
