# objc:objc-exception-object

`(objc:objc-exception-object condition)`

`objc:objc-exception` が持つ送出オブジェクトを返します。参照を 1 つ保持する `objc:objc-object-pointer` で (ポインタが回収されると解放されます)、`nil` が送出された場合は `nil` です。 macOS 専用の `objc` パッケージのコンディションのリーダーです。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defvar *e* (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
                      (objc-exception (e) e)))
*E*
MY-APP> (invoke-into 'string (objc-exception-object *e*) "name")
"NSRangeException"
```
