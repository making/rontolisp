# ネイティブ実行ファイルへのコンパイル

`-o` とともに `--native` を指定すると、プログラムを自己完結した実行ファイル 1 つにコンパイルします: [WASM](wasm.md) 出力を wasmtime でマシンコードへプリコンパイルし、小さなランナーの後ろに付加したものです。実行に wasmtime も JVM も必要ありません:

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp --native -o hello
./hello
```

```lisp
(print (+ 1 2))
```

```
3
```

実行ファイルの中身は `-o hello.wasm` が書き出す WASI コマンドモジュールで、`wasmtime run` と同じように実行されます: 標準出力も終了ステータスも同じです -- プログラム自身の `(uiop:quit n)` のコード、正常に戻れば 0、捕捉されないエラーの後は 134。引数はプログラムの `(uiop:command-line-arguments)` になり、プロセスの環境変数と標準入力も見えます。それ以外のファイルは書き出されません: `.wasm` とそのプリコンパイル結果はメモリ上にしか存在しません。

## ファイル

カレントディレクトリと `/` がプログラムに開かれているので、相対パス(`../x` を含む)も絶対パスも通常のネイティブプログラムと同じように使えます。

## フラグ

デフォルトの WASM 出力のフラグはすべて使えます(`--simd`、`--optimize`、`--dynamic` など)。別の種類のモジュールを求めるフラグは名前を挙げて拒否されます: `--component`、`--no-wasi`、`--no-gc`、`--host-random`、`--host-fetch`、`--host-boundary`、`--reentrant`、`--emit-js-glue`。`.wasm`、`.class`、`.jar`、`.war` で終わる `-o` の名前も同様です。

## 動作環境

実行ファイルはデフォルトでコンパイラを動かしているプラットフォーム(Linux または macOS、x86_64 または aarch64)向けに作られ、そのプラットフォームのすべてのプロセッサが持つ CPU 機能だけを使うので、どれほど古くても同じプラットフォームのマシンなら動きます。Linux では静的リンクされるので、同じアーキテクチャならC ライブラリを問わずどのディストリビューションでも動きます。リリースのバイナリと実行可能 JAR はプリコンパイラを含みます。ホスト向けのプリコンパイラを含まない rontolisp のビルドは `--native is not available for <os>-<arch>` と答えます(ソースからのビルド: [ビルドとインストール](../getting-started/build.md))。

`--native-target` は別のプラットフォーム向けに作ります: `linux-x86_64`、`linux-aarch64`、`macos-aarch64`。リリースのバイナリと実行可能 JAR はそれぞれのランナーを含みます。ソースからのビルドは自分のプラットフォームのランナーだけを含み、別のものを求められると含んでいるものを挙げて答えます。

```bash
rontolisp hello.lisp --native --native-target linux-aarch64 -o hello-arm64
```

`--native-cpu` はマシンコードが使ってよい CPU 機能を選びます: `baseline`(デフォルト)、`host`(コンパイルするマシンの CPU のすべての機能)、x86_64 ではレベル `x86-64-v2`、`x86-64-v3`、`x86-64-v4`。実行ファイルは、その機能のどれかを持たない CPU で起動されると実行を拒否し、欠けている機能を挙げます。ベンチマークプログラムで測ったところ、新しい機能を使っても rontolisp が生成するコードは速くならなかったので、デフォルトのままで実質的な損はありません。

プリコンパイラは共有ライブラリで、`rontolisp` が一度だけ `$XDG_CACHE_HOME/rontolisp`(なければ `~/.cache/rontolisp`、macOS では `~/Library/Caches/rontolisp`)へ展開します。システムプロパティ `rontolisp.native.cache` で場所を変えられます(`-Drontolisp.native.cache=DIR`)。

## サイズと速度

実行ファイルはランナー(Linux x86_64 で 3.0 MB、Linux aarch64 で 2.4 MB、macOS で 1.7 MB)に `.wasm` の約 11 倍を足した大きさです: Linux x86_64 では `hello` で 3.0 MB、28 KB のモジュールで 3.2 MB。起動は約 10 ms で、同じモジュールを `wasmtime run` で動かすのとほぼ同じ速度で動きます。
