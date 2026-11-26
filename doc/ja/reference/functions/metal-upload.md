# metal:upload

`(metal:upload buffer values)`

`values` を `buffer` にコピーします。`buffer` は同じ長さ以上でなければなりません。memcpy の実体は `NSData` の `getBytes:length:` からバッファの `contents` へのコピーです。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (metal:upload *scratch* (metal:floats '(0.0 1.0 0.0)))
```
