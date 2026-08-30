# geom:read-model

`(geom:read-model path &key format color label)`

モデルファイル中のメッシュを [`geom:solid`](geom-polyhedron.md) として返します。フォーマットが分からないファイル、たとえばビューアが渡されたものをそのまま開く場合の唯一の入口です。フォーマットはファイル自身のバイト列から判定します。`:format`（`:obj`、`:stl`）を渡せば判定を飛ばして直接指定できます。フォーマットが分かっているなら [`geom:read-obj`](geom-read-obj.md)、[`geom:read-stl`](geom-read-stl.md) と名指しするほうが成果物は小さくなります。この関数はすべてのリーダーに到達しうるので、すべてのリーダーを抱え込みます。

`:color` と `:label` はパッケージ内の他のコンストラクタとまったく同じくソリッドのものです。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```console
CL-USER> (geom:read-model "bunny.obj" :color (geom:vec3 0.85 0.72 0.5))
#<GEOM:SOLID 35947 vertices 69451 facets>
CL-USER> (geom:read-model "part.stl" :label "bracket")
#<GEOM:SOLID "bracket" 26862 vertices 8954 facets>
```

## フォーマットの判定方法

まず内容から判定し、内容では答えようのないところだけ名前に頼ります。

| テスト | 判定 |
|---|---|
| 先頭が `glTF` | glTF-Binary |
| 先頭が `ply` と改行 | PLY |
| 最初のトークンが `solid` | STL（ASCII） |
| 最初のトークンが `v`、`vn`、`vt`、`f`、`g`、`o`、`s`、`usemtl`、`mtllib` のいずれか | OBJ |
| 最初のトークンが `{` で始まる | glTF（JSON） |
| それ以外 | ファイルの拡張子 |

**バイナリ STL にはマジックナンバーがまったくありません** — フォーマット側の不備です — そのため 80 バイトのヘッダが `solid` で始まらないバイナリ `.stl` は拡張子で名指しされます。拡張子が決して決められないのは ASCII/バイナリの区別のほうで、どちらの方言も `.stl` であり、`geom:read-stl` がバイト列からそれを決めます。

PLY と glTF は認識はしますが読みません。このビルドが扱えないファイルは、でたらめに解析されるのではなく名指しで拒否されます。

```console
CL-USER> (geom:read-model "dragon.ply")
; Error: geom:read-model: dragon.ply is PLY, which this build does not read yet
```

## バックエンド対応

ファイルシステムを持つすべてのバックエンド、すなわちインタプリタ、コンパイル済み `.class`/`.jar`、WASM Preview 1、WASI 0.3 コンポーネントで、4 つとも同じ答えを返します。ブラウザプレイグラウンドにはファイルシステムがありませんが、モデルファイルを読まないプログラムはリーダーをそもそも抱えません。コンパイル出力から刈り取られます。
