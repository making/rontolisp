# checkpoint:skip-bytes

`(checkpoint:skip-bytes stream n)`

バイトストリーム `stream` の `n` バイトを、64 KB のスクラッチバッファを通した有界の読み取りで読み飛ばします。`n` を返します。`file-position` はすべてのバックエンドで `nil` を返す（ストリームはシークできない）ので、リーダーはファイルを先頭から順に歩き、読み込まないよう指示されたものをこの方法で飛ばします。そのテンソルのバイト列は I/O のコストだけを払い、何もステージングされません。

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s 8))
8
```
