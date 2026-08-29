# scene:camera

`(scene:camera v &key azimuth elevation distance target)`

4 つのカメラパラメータのうち与えられたものだけを設定し、残りはそのままにします。カメラは `target` を中心に `distance` の距離で、`azimuth` は z 軸まわり、`elevation` は地面からの仰角です (マウス操作では ±1.5 ラジアンに制限されますが、この関数では制限しません)。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:camera *v* :azimuth 0.85 :elevation 0.42 :distance 1250)
NIL
```
