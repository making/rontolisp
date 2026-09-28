# objc:objc-exception-reason

`(objc:objc-exception-reason condition)`

`objc:objc-exception` が持つ例外の `reason` を文字列で返します。理由がなければ `nil` です (`NSException` でない送出オブジェクトには理由がありません)。 macOS 専用の `objc` パッケージのコンディションのリーダーです。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defvar *e* (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
                      (objc-exception (e) e)))
*E*
MY-APP> (objc-exception-reason *e*)
"*** -[__NSArray0 objectAtIndex:]: index 5 beyond bounds for empty array"
```
