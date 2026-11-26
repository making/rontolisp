# checkpoint:skip-bytes

`(checkpoint:skip-bytes stream n)`

バイトストリーム `stream` の `n` バイトを読み飛ばし、`n` を返します。ストリームが位置を答える場合は読み飛ばしの代わりにそこまでシークします（セットはパーク中の入力を捨てます）。大きな不要テンソルのスキップに I/O はかかりません。そうでない場合（ソケット、パイプ、位置を持たないストリーム）は従来通り 64 KB のスクラッチバッファを通した有界の読み取りで歩きます。どちらでもテンソルのバイト列がステージングされることはありません。

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s 8))
8
```
