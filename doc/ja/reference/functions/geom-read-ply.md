# geom:read-ply

`(geom:read-ply path &key color label)`

PLY(Stanford ポリゴン形式)ファイル中のメッシュを [`geom:solid`](geom-polyhedron.md) として返します。本体は `ascii` と `binary_little_endian` の両方を読みます。`binary_big_endian` は名指しで拒否します。バルクのバイナリ読み込みは契約上リトルエンディアンであり、すべての float を読み違えるより、そう言うほうがずっとましだからです。

ヘッダが本体を記述します。すべての要素、その個数、すべてのプロパティとその型です。したがって `x`、`y`、`z` は頂点要素がどこに置いていようとそこから取り、それ以外のプロパティは推測ではなく**宣言された幅ぶんだけ読み飛ばします**。Stanford バニーの `confidence` と `intensity` の列、スキャナの頂点ごとの色、インデックスリストの後ろの面ごとの色などです。`geom:solid` は色を 1 つしか持たないので、頂点ごと・面ごとの色は平均されるのではなく捨てられます。`face` 要素のないファイル(生のレンジスキャン)はエラーではなく、ファセットのない頂点群を返します。

```console
CL-USER> (geom:read-ply "bun_zipper.ply" :color (geom:vec3 0.85 0.72 0.5))
#<GEOM:SOLID 35947 vertices 69451 facets>
CL-USER> (geom:volume *)
7.700565237386743e-4
```

頂点プロパティがすべて float32 のバイナリ本体は、パックされた single-float 配列への [`read-sequence`](read-sequence.md) 1 回で移されます。頂点ブロック全体が 1 回のネイティブ転送です。色が挟まるとストライドが不均一になり、頂点あたり読み込みが 1 回増えます。面は 1 つあたり 2 回の転送(個数 1 回、インデックスのバルク読み 1 回)で、[`geom:read-stl`](geom-read-stl.md) の三角形あたり 2 回と同じ形です。ASCII 本体は [`geom:read-obj`](geom-read-obj.md) と同じく 1 文字ずつ走査します。

## バックエンド対応

ファイルシステムを持つすべてのバックエンド、すなわちインタプリタ、コンパイル済み `.class`/`.jar`、WASM Preview 1、WASI 0.3 コンポーネントで、4 つとも同じ答えを返します。
