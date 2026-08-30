# geom:read-stl

`(geom:read-stl path &key color label)`

STL ファイル中のメッシュを [`geom:solid`](geom-polyhedron.md) として返します。両方の方言を読み、**どちらで書かれているかはファイル自身の形から判定します**。拡張子でも先頭の単語でもありません。バイナリライタが 80 バイトのヘッダに `solid <name>` を書き込むのはごく普通のことだからです。ASCII ファイルは `solid` というトークンで始まり、次の行に `facet` か `endsolid` を持ちます。それ以外はバイナリです。

STL はインデックステーブルを持たない三角形の寄せ集めなので、ソリッドは**面あたり 3 頂点**を抱えます。8,954 三角形は 26,862 頂点として返ります。ファイルが格納する面法線は無視します。`geom` は形状から Newell 法で自前に計算しますし、世の中のライタの半分はそこにゼロを書き込んでいます。

```console
CL-USER> (geom:read-stl "part.stl" :color (geom:vec3 0.45 0.7 0.92))
#<GEOM:SOLID 26862 vertices 8954 facets>
CL-USER> (geom:volume *)
16.084489098307944
```

バイナリファイルの三角形あたり 12 個の float32 は、パックされた single-float 配列への [`read-sequence`](read-sequence.md) 1 回で移されるので、読み込みに数値ごとの演算はまったくかかりません。ASCII ファイルは [`geom:read-obj`](geom-read-obj.md) と同じく 1 文字ずつ走査します。

## バックエンド対応

ファイルシステムを持つすべてのバックエンド、すなわちインタプリタ、コンパイル済み `.class`/`.jar`、WASM Preview 1、WASI 0.3 コンポーネントで、4 つとも同じ答えを返します。方言の判定は長さではなく形を読みます。コードパスは 1 つで 4 バックエンドとも同一であり、サイズをまったく必要としません。
