# objc:with-objc-block

`(objc:with-objc-block (var type function) form*)`

`objc:make-objc-block` が `type` と `function` から作ったブロックに `var` を束縛して `form` を評価し、どの脱出でもブロックを解放して、最後のフォームの値を返します。非同期の処理にもそのまま使えます。ブロックを保持する呼び出し先はコピーを持ち、フォームが戻った後もそのコピーが `function` を生かします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (with-objc-block (each '(:void (objc-object-pointer (:unsigned :long-long)
                                        (:pointer objc-c++-bool)))
                               (lambda (word index stop)
                                 (declare (ignore stop))
                                 (format t "~a ~a~%" index (ns-string-to-string word))))
          (invoke *words* "enumerateObjectsUsingBlock:" each))
0 pear
1 fig
2 apple
NIL
```
