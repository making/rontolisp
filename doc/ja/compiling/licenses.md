# コンパイルしたプログラムのライセンス

rontolisp がコンパイルしたプログラムには、利用者自身が書いたものではないコードが含まれます。
プログラムを配布するときは、そのコードのライセンス表示も一緒に渡す必要があります。

## コンパイルしたプログラムに含まれるもの

- **rontolisp のランタイムコード**: すべての出力 (`.class`、`.jar`、`.war`、`.wasm`、ネイティブ
  実行ファイル) に含まれます。rontolisp のライセンスは Apache License 2.0 で、配布物にライセンスの
  写しと rontolisp の `NOTICE` を添えることを求めています。
- **rontolisp が取り込んでいる第三者のコードとデータ**: 使う出力にだけ含まれます。大文字小文字の
  変換や文字分類の Unicode テーブル、WASM バックエンドの数学関数 (fdlibm) と浮動小数点数の印字
  (Schubfach)、組み込みの Ring と riddley の名前空間、ASDF・UIOP・CFFI のランタイム、tiny-routes の
  パステンプレート、Starlark の `str.format`、Q8_0 量子化 (ggml)、`--component` の WASI
  インターフェースです。
- **ネイティブランナー**: [ネイティブ実行ファイル](native.md) に含まれます。ランナーは wasmtime と
  約 110 個の Rust クレート (MIT、Apache-2.0、BSD-3-Clause、ISC、Unicode-3.0、Zlib、
  CDLA-Permissive-2.0) をリンクしており、これらのライセンスはバイナリ配布にライセンス表示を
  添えることを求めています。

## ライセンス表示の取得

`rontolisp notices` は、これらをまとめて 1 つのファイルに書き出します。プログラムと一緒に配布して
ください:

```console
rontolisp notices -o NOTICES.txt                  # .class、.jar、.war、.wasm の場合
rontolisp notices --native -o NOTICES.txt         # このマシン向けのネイティブ実行ファイルの場合
rontolisp notices --native --native-target linux-aarch64 -o NOTICES.txt
```

ファイルには rontolisp のライセンスと `NOTICE`、続いてコンパイルしたプログラムが含みうる第三者の
コードとデータのすべての項目 (そのプログラムが実際に使うかどうかを問いません)、`--native` を
付けた場合はランナーのクレートが、それぞれのライセンス本文とともに入ります。

## プログラムが読み込むライブラリ

プログラムが読み込むライブラリ (Quicklisp などの dist、`deps.edn` の依存、`--java-dep` の座標) は、
プログラムと一緒に出力へコンパイルされますが、そのライセンス表示は自動では出力に入りません。
`rontolisp notices` もそれらを含めません。ライセンスに従う責任は利用者にあります。多くの
ライセンスは著作権表示とライセンス本文 (ライブラリの `LICENSE` ファイル) をプログラムに添えることを
求めます。コピーレフトのライセンス (LGPL や LLGPL、GPL、AGPL、EPL) は、ライブラリのソースコードの
提供などを追加で求めることがあります。

## rontolisp 自体

`rontolisp notices --tool` は、rontolisp の jar やネイティブバイナリ自体が含むものを出力します:
rontolisp のライセンスと `NOTICE`、取り込んでいる第三者のコード、JLine、プリコンパイル用シムと
ランナーの Rust クレートです。jar の `META-INF/` にも同じものが入っています。
