# fli:with-dynamic-foreign-objects

`(fli:with-dynamic-foreign-objects ((var type &key nelems initial-element initial-contents fill)*) form*)`

各 `var` を FLI 型 `type` (評価しません。キーは [`fli:allocate-foreign-object`](../functions/fli-allocate-foreign-object.md) と同じです) の外部オブジェクトに束縛して `form` を評価し、どのように抜けてもすべて解放して、最後の form の値を返します。LispWorks はスタックに確保しますが、ここではヒープのメモリです。form の外に持ち出したポインタが無効になる点は同じです。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (fli:with-dynamic-foreign-objects ((result-value :int))
          (invoke (invoke "NSScanner" "scannerWithString:" "42 apples") "scanInt:" result-value)
          (fli:dereference result-value))
42
```
