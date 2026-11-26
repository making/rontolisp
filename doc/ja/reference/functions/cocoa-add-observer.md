# cocoa:add-observer

`(cocoa:add-observer target selector &key name object center)`

`target` (オブジェクト、または `objc:standard-objc-object`) に、`object` が投稿する名前 `name` (文字列) の通知を監視させます。`nil` はすべてに一致します。通知ごとに、`NSNotification` を受け取るメソッド `selector` が送られます。`center` の既定は既定の通知センターです。`nil` を返します。 macOS 専用の `cocoa` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-class watcher ()
          ((seen :initform nil :accessor seen))
          (:objc-class-name "Watcher"))
WATCHER
MY-APP> (define-objc-method ("noticed:" :void) ((self watcher) (note objc-object-pointer))
          (push (invoke-into 'string note "name") (seen self)))
"noticed:"
MY-APP> (defvar *w* (make-instance 'watcher))
*W*
MY-APP> (cocoa:add-observer *w* "noticed:" :name "Ping")
NIL
MY-APP> (invoke (invoke "NSNotificationCenter" "defaultCenter")
                "postNotificationName:object:" "Ping" nil)
NIL
MY-APP> (seen *w*)
("Ping")
MY-APP> (cocoa:remove-observer *w* :name "Ping")
NIL
```
