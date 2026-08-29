# metal:library

`(metal:library ctx source)`

Metal Shading Language の `source` を実行時にコンパイルし `MTLLibrary` を返します。コンパイルに失敗したシェーダは、Metal コンパイラ自身の診断 (行とキャレット付き) を持つ通常の Lisp コンディションを送出します。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *lib* (metal:library *ctx* *shaders*))
CL-USER> (handler-case (metal:library *ctx* "nonsense") (error (e) :rejected))
:REJECTED
```
