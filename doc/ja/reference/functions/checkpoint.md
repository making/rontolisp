# checkpoint パッケージの関数

`checkpoint` パッケージは、公開されたモデルのテンソルをパックされた浮動小数点配列に
ステージングします。チェックポイントを読む処理のうち、ファイル形式によらず共通する半
分です。[`safetensors`](safetensors.md) リーダーはこの上に書かれており、GGUF リーダー
も同じです。rontolisp 自身で書かれ、`geom` と同じように最初の使用時に読み込まれま
す。依存するのはバイトストリームのプリミティブと
[`rontolisp:widen-float-bits`](widen-float-bits.md) だけなので、ファイルシステムのある
すべてのバックエンドで動きます。**Common Lisp の一部ではありません**。名前は
`checkpoint:` 修飾子で参照します。

設計を決めている事実は 3 つです。ストリームは位置を変えられない（`file-position` は
`nil` を返す）ので、リーダーはファイルを先頭から順に歩き、要らないものは
`checkpoint:skip-bytes` で読み飛ばします。パックされた `(unsigned-byte 16)` ベクタは
インタプリタと JVM で 1 要素 8 バイトを占めるので、f16 / bf16 のビットは 100 万要素
ずつ、1 本の再利用バッファを通してステージングされます -- `stage-float-bits` が受け取
るのはストリームであって、丸ごとステージングしたテンソルではありません。そして
`make-array :element-type` は知らない型に対して boxed な配列を返すので、`make-tensor`
が唯一の確保経路であり、得たものを検証します。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `checkpoint:make-tensor` | `(checkpoint:make-tensor '(2 3) 'single-float)` | その形のパックされた浮動小数点配列。パックされていることを検証済み |
| `checkpoint:stage-float-bits` | `(checkpoint:stage-float-bits s 4096 :bfloat16 dst)` | ストリームから読んだ 4096 個の bf16 ワードを `dst` に拡張 |
| `checkpoint:stage-float32` | `(checkpoint:stage-float32 s dst)` | F32 テンソルを `dst` に直接読み込み |
| `checkpoint:skip-bytes` | `(checkpoint:skip-bytes s 1048576)` | 1 メガバイトを有界の読み取りで読み飛ばし |
