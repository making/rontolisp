# fli:foreign-slot-value

`(fli:foreign-slot-value pointer slot-name &key type object-type copy-foreign-object)`

`pointer` が指す構造体 (`object-type` を与えればその型) のスロット `slot-name` を返します。名前のリストで入れ子の構造体の中を指せます。スロットは `objc:define-objc-struct` が宣言したもの、および `cocoa:ns-rect` の `origin` / `size`、`cocoa:ns-point` の `x` / `y`、`cocoa:ns-size` の `width` / `height`、`cocoa:ns-range` の `location` / `length` で、パッケージを問わず名前で照合します。値の読み方は [`fli:dereference`](fli-dereference.md) と同じで、`copy-foreign-object` も同じです。`setf` で書き込めます。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:with-dynamic-foreign-objects ((rect cocoa:ns-rect))
           (objc:invoke-into rect (objc:invoke "NSValue" "valueWithRect:" #(0 0 640 480)) "rectValue")
           (fli:foreign-slot-value rect '(:size :width)))
640.0
```
