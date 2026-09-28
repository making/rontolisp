# fli:define-foreign-function

`(fli:define-foreign-function name (arg*) &key result-type module variadic-num-of-fixed)`

LispWorks の `fli:define-foreign-function` のうち、`objc` パッケージの例が使う部分です。`name` を、C 関数 `foreign-name` を呼ぶ Lisp 関数として定義します。関数はプロセスに読み込まれたどのイメージのものでも構いません (libSystem、フレームワーク、`objc:ensure-objc-initialized` や `:module` で読み込んだモジュール)。`name` は `lisp-name` か `(lisp-name foreign-name)` で、外部名を省くと Lisp 名を小文字にしてハイフンをアンダースコアにしたものになります。引数は `(arg-name type)` か `(:constant value type)` で、型は `objc:invoke` のリスト形式と同じものを取り、同じように変換されます (ブロックは `objc:objc-at-question-mark`)。`:result-type` の既定値は `:int` です。オブジェクトの結果は、関数名が生成か複製を表すとき (`dispatch_queue_create`、`CFStringCreateCopy`) は所有権ごと受け取り、それ以外は retain します。`:variadic-num-of-fixed` はその数より後の引数を可変長引数にします。関数は呼び出し元のスレッドで実行されます。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは呼び出しが `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (fli:define-foreign-function (dispatch-queue-create "dispatch_queue_create")
            ((label objc-c-string) (attributes :pointer))
          :result-type objc-object-pointer)
DISPATCH-QUEUE-CREATE
MY-APP> (fli:define-foreign-function (dispatch-async "dispatch_async")
            ((queue objc-object-pointer) (work objc-at-question-mark))
          :result-type :void)
DISPATCH-ASYNC
MY-APP> (defvar *queue* (dispatch-queue-create "com.example.work" nil))
*QUEUE*
```
