# 手書きひらがな認識 (rontolisp で学習 → WASM → ブラウザ canvas)

5 クラス（あ・い・う・え・お）の小さな多層パーセプトロン (MLP) を rontolisp で書き、
**オフラインで学習**した重みを推論専用プログラムに焼き込んで WebAssembly にコンパイルし、
ブラウザの `<canvas>` に書いた文字を認識させるデモです。

学習（バックプロパゲーション）はコストが高く非決定的なので一度だけオフラインで行い、
ブラウザでは**焼き込んだ重みで推論するだけ**にしています。同じ推論プログラムは
インタプリタ・JVM・WASM のいずれでも動きます。

`examples/wasm-browser/` がコンパイル済み `.wasm` をブラウザで動かす最小例なのに対し、
こちらはそこに「機械学習」と「canvas 入力」を載せたものです。WASI Preview1 シムは
`examples/wasm-browser/wasi-shim.js` と同一のものをコピーして使っています。

## 仕組み

```
[オフライン]  common.lisp + prototypes.lisp + train-main.lisp = train.lisp
              -> (JVM で実行) 学習し、重みを weights.lisp として出力
              common.lisp + weights.lisp + infer-main.lisp = infer.lisp
              -> rontolisp infer.lisp -o infer.wasm   (WASI Preview1, WASM GC)

[ブラウザ]    canvas 描画 -> JS が 16x16 に縮小・中心化・二値化して平坦化
              -> "(0.0 1.0 ... )" を stdin として infer.wasm に渡す (wasi-shim.js)
              -> stdout "pred <i> <romaji>" を読み、ひらがなで表示
```

- 画像は **16x16 グリッド**を行優先で平坦化した長さ 256 のベクトル（値は 0/1）。
- ネットワークは **256 - 32 - 5**。出力 5 ユニット + one-hot ターゲット + argmax で多クラス分類
  （`examples/mlp.lisp` の sigmoid + 二乗誤差バックプロップをそのまま多出力化）。
- 参考字形は実フォント（Hiragino Maru Gothic ProN）を 16x16 に縮小・二値化したもの
  （`prototypes.lisp`）。これを **平行移動 (±1px) とノイズで水増し**して学習。外部データ
  セット非依存で自己完結。

### 重みの焼き込みと「読み込めるか」

重みは `infer.lisp` に Lisp ソースとして埋め込まれ、**コンパイル時にホストの `LispReader`
（JDK 完全版）が読みます**。したがって精度の劣化はなく、WASM 実行時リーダ（整数は i31、
小数は指数なし）を通るのは **stdin のビットマップだけ**です。ブラウザ側は `0.0`/`1.0` の
素の小数しか送らないので安全です。

なお重みは多数の小さな `(defun gN () (list ...))` チャンク関数に分割して出力しています。
JVM バックエンドは 1 メソッドのバイトコードが 64KB に制限されるため、巨大な単一リテラルを
避ける必要があるからです（インタプリタ／WASM には不要ですが、同じ形でそのまま動きます）。

## ビルド

リポジトリルートで JAR を用意してから `gen.sh` を実行します。

```bash
./mvnw clean package                 # target/rontolisp-...-exec.jar を生成
examples/hiragana/gen.sh             # 学習 (約数秒) して infer.wasm を生成
```

`gen.sh` は学習を **JVM コンパイル経由**で行うため数秒で終わります。学習の進捗は
`weights.lisp` の中に `;;` コメント行として残ります（リーダは無視します）。

## ブラウザで動かす

`fetch` で `.wasm` を読むため `http://` で配信してください。

```bash
python3 -m http.server 8000 --directory examples/hiragana
# ブラウザで http://localhost:8000/ を開く
```

マスに字を書いて「認識」を押すと予測クラスとスコアが出ます。下段の参考字形に
寄せて書くとよく当たります。

**ブラウザ要件**: WebAssembly GC 対応（Chrome/Edge 119+, Firefox 120+, Safari 18.2+）。

## ブラウザなしで試す（インタプリタ / JVM）

canvas はブラウザ専用ですが、認識ロジックはバックエンド非依存です。`samples/` に各クラスを
平坦化したビットマップ（`(0.0 1.0 ...)` 1 行）を置いてあります。

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
S=examples/hiragana/samples

# インタプリタ
java -jar $JAR examples/hiragana/infer.lisp < $S/u.txt        # -> pred 2 u

# JVM (クラス名は出力ファイル名になるのでパスを含めない)
cd examples/hiragana && java -jar ../../$JAR infer.lisp -o Infer.class && java Infer < samples/u.txt

# WASM Preview1
java -jar $JAR examples/hiragana/infer.lisp -o /tmp/infer.wasm && wasmtime run -W gc /tmp/infer.wasm < $S/u.txt
```

## ファイル

| ファイル                       | 役割                                                        |
| ------------------------------ | ----------------------------------------------------------- |
| `common.lisp`                  | 推論・学習で共有する数値演算（全バックエンドで動く範囲のみ）  |
| `prototypes.lisp`              | 各クラスの 16x16 字形テンプレート（実フォント由来・学習データの種） |
| `train-main.lisp`              | オフライン学習本体（データ拡張・SGD・重みのシリアライズ）      |
| `infer-main.lisp`              | 推論本体（stdin から読み、forward、クラスを出力）            |
| `gen.sh`                       | 学習 → `infer.wasm` 生成のパイプライン                       |
| `index.html`                   | canvas で描いて認識するブラウザページ                       |
| `wasi-shim.js`                 | WASI Preview1 シム（`../wasm-browser/` と同一のコピー）       |
| `samples/*.txt`                | ブラウザなし確認用の平坦化ビットマップ                       |
| `infer.wasm`                   | 学習済み重みを焼き込んだ推論モジュール（生成物・コミット対象） |
| `train.lisp` / `infer.lisp` / `weights.lisp` | `gen.sh` が生成する中間ファイル                |

## 限界

- 16x16・5 クラス・テンプレート＋軽い拡張のみなので、汎化は限定的です（デモとして
  「だいたい当たる」水準）。参考字形に寄せて書くのが前提です。
- ファイル I/O はブラウザのシムで未対応のため、入力は stdin 経由のみです。
- クラスを増やす・解像度を上げる・実データで学習する、といった拡張は
  `prototypes.lisp` と `train-main.lisp` の定数（`*hidden*` 等）を変えれば可能ですが、
  重み・学習時間が増えます。
