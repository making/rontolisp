# geom:read-gltf

`(geom:read-gltf path &key color label)`

glTF 2.0 ファイル中のシーンを **[`geom:solid`](geom-polyhedron.md) のリスト**として返します。メッシュプリミティブ 1 つにつきソリッド 1 つで、それぞれマテリアルの `baseColorFactor` で着色されます(`:color` はすべてを上書きします)。キャリアは両方読みます。`.glb`(JSON チャンク + BIN チャンク)と、バッファが隣の `.bin` ファイルまたは base64 `data:` URI である `.gltf` です。リモート(`http:`)のバッファ URI は拒否します。これはファイルリーダであり、フェッチャではありません。

glTF はメッシュではなくシーンであり、ノード階層は読み込み後も生きています。glTF の各ノードは translation/rotation/matrix で姿勢づけられた [`geom:node`](geom-make-node.md) になり、各ソリッドはそのノードの下にアタッチされ、答えはフラットなリストです。したがって [`scene:add`](scene-add.md) はそのまま展開し、[`geom:bounds`](geom-bounds.md) は全体を 1 つとして測り、各ソリッドの [`geom:world-transform`](geom-world-transform.md) は親たちを引き連れます。複数パーツのモデルは、ノードの言うとおりの場所に配置されます。

**ノードのスケールはプリミティブの頂点に焼き込まれます**。木を下りながら積算され、子の translation には上位の積が掛かります。[`geom:transform`](geom-make-transform.md) は設計上剛体なので、2 倍スケールのノードの立方体は、測定が見えないスケールを抱える代わりに、体積が 8 倍と*測定される*のです。この合成は一様スケールでは厳密です。回転した子の上の非一様スケールはせん断を生み、剛体変換では表せないため、名指しで拒否します。

```console
CL-USER> (geom:read-gltf "Duck.glb")
(#<GEOM:SOLID "LOD3spShape" 2399 vertices 4212 facets>)
CL-USER> (geom:volume (first *))
1.1957991851442398
```

JSON は [`rontolisp:json-parse`](rontolisp-json-parse.md) を通り、バッファはパック配列への [`read-sequence`](read-sequence.md) を通ります。bufferView とはまさにそれです。POSITION は 1 回のネイティブ転送、インデックスももう 1 回です。base64 `data:` URI だけは float32 の組み立てを含めて Lisp の算術でデコードするので、数メガバイトの埋め込みバッファは遅い経路になります。

## 名指しで拒否するもの

glTF の黙った部分読みは拒否より悪いので、それぞれが自分の名を言います。三角形以外のプリミティブ `mode`、疎(sparse)アクセサ、`extensionsRequired` のすべてのエントリ(Draco や meshopt 圧縮はここから来ます)、スキン、アニメーション、glTF 1.x です。テクスチャ、頂点ごとの法線、テクスチャ座標は読み飛ばします。ソリッドは色を 1 つ持ち、法線は自前で計算するからです。

## バックエンド対応

ファイルシステムを持つすべてのバックエンド、すなわちインタプリタ、コンパイル済み `.class`/`.jar`、WASM Preview 1、WASI 0.3 コンポーネントで、4 つとも同じ答えを返します。
