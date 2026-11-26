# objc:objc-exception-name

`(objc:objc-exception-name condition)`

`objc:objc-exception` が持つ例外の名前を文字列で返します (`"NSRangeException"`)。送出されたのが `NSException` でなければそのクラス名、`nil` が送出された場合は `"nil"` です。`objc:objc-exception` は、Objective-C の例外が送出されたときに実行中だった最も内側の `objc:invoke`、C 関数またはブロックの呼び出しがシグナルします。 macOS 専用の `objc` パッケージのコンディションのリーダーです。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defvar *e* (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
                      (objc-exception (e) e)))
*E*
MY-APP> (objc-exception-name *e*)
"NSRangeException"
```
