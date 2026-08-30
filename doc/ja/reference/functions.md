# 関数

関数リファレンスはパッケージごとに1ページずつに分かれています。**各パッケージの表にある関数名はそれぞれのページにリンクしています**。各ページには、より詳しい説明と、ブラウザで評価できる実行可能な例があります。横断的なトピックには専用の場所があります。`make-array`/`aref`
とハッシュテーブル演算子は、データ型ページの
[配列](data-types.md#arrays) と [ハッシュテーブル](data-types.md#hash-tables)
で説明されており、各関数の Common Lisp との違いはそれぞれのページに記載されています。

## パッケージ

| パッケージ | 関数 |
|---------|-----------|
| [`cl`](functions/cl.md) | 標準の Common Lisp 関数。`cl-user` が修飾なしで利用します |
| [`rontolisp`](functions/rontolisp.md) | 実装固有の関数: `version`、非同期 HTTP フェッチ/サーブ、JSON、TCP/TLS ソケット、WASM/JVM エクスポートと WIT フック |
| [`linalg`](functions/linalg.md) | numpy スタイルのベクトル・行列演算 |
| [`torch`](functions/torch.md) | 自動微分と `nn` スタイルのモジュール層を備えた PyTorch スタイルのテンソル |
| [`java`](functions/java.md) | リフレクションによる Java 連携 (JVM インタプリタのみ) |
| [`ffi`](functions/ffi.md) | C ライブラリ連携 |
| [`objc`](functions/objc.md) | Foreign Function API による Objective-C ランタイムと AppKit (macOS のインタプリタのみ) |
| [`appkit`](functions/appkit.md) | `objc` の上の Cocoa ウィジェット層 |
| [`geom`](functions/geom.md) | `linalg` カーネル上のソリッドモデリング |
| [`metal`](functions/metal.md) | `objc` の上の `appkit` ウィンドウ上に載る Metal 描画サーフェス |
| [`scene`](functions/scene.md) | `metal` の上に載る `geom` ソリッドの 3D ビューア |
| [`asdf`](functions/asdf.md) | ASDF の限定的な API 互換サブセット(システム定義) |
| [`uiop`](uiop.md) | ASDF の移植性レイヤ -- 15 個のサブパッケージ、実装状況、関数の表 |
| [`ql` / `ql-dist`](functions/ql-and-ql-dist.md) | Quicklisp の限定的な API 互換サブセット |
| [`usocket`](functions/usocket.md) | `rontolisp:tcp-*` 組み込みの上に載った [usocket](https://github.com/usocket/usocket) 互換シム |

パッケージシステム全体 (`:use`、修飾子、`defpackage`) については
[パッケージ](packages.md) を参照してください。
