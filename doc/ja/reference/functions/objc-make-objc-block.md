# objc:make-objc-block

`(objc:make-objc-block type function)`

`function` を呼ぶ Objective-C ブロックを作り、`objc:objc-block` を返します。この値は、ブロックを受け取るメソッドや C 関数にそのまま渡せます。`type` は `objc:invoke` のリスト形式と同じ型で書いた `(result-type (argument-type*))`、または `objc:define-objc-block-type` で定義した名前です。ブロックの引数はその型で変換されて `function` に渡り (メソッドの本体に渡る引数と同じです)、`function` の値は逆向きに変換されます。`function` の中のエラーは表示され、ブロックは 0 を返します。ブロックは呼んだスレッドで実行されます (完了ハンドラなら libdispatch のワーカー)。ただし `--native` 実行ファイルでは、別スレッドから呼ばれた `void` のブロックはメインスレッドのイベントループ (プログラムの `sleep`) を待って実行され、値を返すブロックはそこで 0 を返します。`objc:free-objc-block` で解放するか、`objc:with-objc-block` で作ります。ブロックを保持する呼び出し先はコピーを持ち、解放後もそのコピーが `function` を生かします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defvar *add* (make-objc-block '(:int (:int :int)) (lambda (a b) (+ a b))))
*ADD*
MY-APP> *add*
#<OBJC:OBJC-BLOCK i@?ii live>
MY-APP> (call-objc-block '(:int (:int :int)) *add* 3 4)
7
```
