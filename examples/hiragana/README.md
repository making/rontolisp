# 手書きひらがな認識 (rontolisp で学習 → WASM → ブラウザ canvas)

全 46 文字（五十音 あ〜ん、濁点・拗音は除く）の小さな多層パーセプトロン (MLP) を
rontolisp で書き、**オフラインで学習**した重みを推論専用プログラムに焼き込んで
WebAssembly にコンパイルし、ブラウザの `<canvas>` に書いた文字を認識させるデモです。

学習（バックプロパゲーション）はコストが高く非決定的なので一度だけオフラインで行い、
ブラウザでは**焼き込んだ重みで推論するだけ**にしています。同じ推論プログラムは
インタプリタ・JVM・WASM のいずれでも動きます。

`examples/wasm-browser/` がコンパイル済み `.wasm` をブラウザで動かす最小例なのに対し、
こちらはそこに「機械学習」と「canvas 入力」を載せたものです。WASI Preview1 シムは
`examples/wasm-browser/wasi-shim.js` と同一のものをコピーして使っています。

## 仕組み

```
[オフライン]  train.lisp が common.lisp + prototypes.lisp を (load ...)
              -> (JVM で実行) 学習し、重みを weights.lisp として出力
              infer.lisp が common.lisp + weights.lisp を (load ...)
              -> rontolisp infer.lisp -o infer.wasm  (WASI Preview1, WASM GC)

[ブラウザ]    canvas 描画 -> JS が 24x24 に縮小・中心化・二値化して平坦化
              -> "(0.0 1.0 ... )" を stdin として infer.wasm に渡す (wasi-shim.js)
              -> stdout "pred <i> <romaji>" を読み、ひらがなで表示
```

各ファイルは連結ではなく `(load ...)` で合成します。コンパイラではトップレベルのリテラル
`(load ...)` は**コンパイル時インクルード**として展開されるので、読み込んだ `defun` は
ネイティブにコンパイルされ（連結と同一）、インタプリタはランタイムで読み込みます。相対パスは
**その `load` を書いたファイルからの相対**で解決されるため、どのディレクトリから実行しても
動きます。

- 画像は **24x24 グリッド**を行優先で平坦化した長さ 576 のベクトル（値は 0/1）。
- ネットワークは **576 - 20 - 46**。出力 46 ユニット + one-hot ターゲット + argmax で多クラス分類
  （`examples/mlp.lisp` の sigmoid + 二乗誤差バックプロップをそのまま多出力化）。
- 参考字形は実フォントを 24x24 に縮小・二値化したもので、`glyphgen/GlyphGen.java`（オフライン
  の Java レンダラ）が `prototypes.lisp`（学習用）と `glyphs.js`（ブラウザ表示用）を**同一
  ソースから生成**します。手書きのばらつきに耐えるため、学習データは次のように水増しします。
  - **複数フォント**（Hiragino Maru Gothic ProN・Klee・YuGothic・Hiragino Mincho ProN）で
    各文字を描画。書体ごとに「はね」やはらい・筆運びの形が違うので、字形のばらつきを学べます
    （表示する参考字形は先頭の Maru Gothic）。
  - **平行移動・線の太らせ (dilate)・ノイズ・回転/せん断 (アフィン変形)** を各テンプレートに適用。
  - 外部データセット非依存で自己完結。

### 重みの焼き込みと「読み込めるか」

重みは `weights.lisp` から `infer.lisp` に `(load ...)` で取り込まれ、コンパイル時
インクルードとして**ホストの `LispReader`（JDK 完全版）が読みます**。したがって精度の劣化はなく、
WASM 実行時リーダ（整数は i31、
小数は指数なし）を通るのは **stdin のビットマップだけ**です。ブラウザ側は `0.0`/`1.0` の
素の小数しか送らないので安全です。

なお重みは多数の小さな `(defun gN () (list ...))` チャンク関数に分割して出力しています。
JVM バックエンドは 1 メソッドのバイトコードが 64KB に制限されるため、巨大な単一リテラルを
避ける必要があるからです（インタプリタ／WASM には不要ですが、同じ形でそのまま動きます）。

加えて JVM では**クラス全体**にも制限があり、焼き込む浮動小数定数が概ね 1.3 万個を超えると、
スタックマップを持たないクラス版 50 のバイトコードが JDK 25 のベリファイアを通らなくなります
（`infer.wasm` への JVM 版確認ができなくなる）。このため隠れ層は 20（重み合計 = 623×20+46 ≈ 1.25 万）に
抑えています。インタプリタと WASM にこの制限はありません。隠れ層を増やすと WASM 版だけは
動きますが、JVM 版の確認はできなくなります。

## ビルド

リポジトリルートで JAR を用意してから `gen.sh` を実行します。

```bash
./mvnw clean package                 # target/rontolisp-...-exec.jar を生成
examples/hiragana/gen.sh             # 学習 (数分) して infer.wasm を生成
```

`gen.sh` は学習を **JVM コンパイル経由**で行うため数分で終わります（複数フォント×アフィン
水増しでデータが増えたぶん、5 クラス時代より時間がかかります）。学習の進捗は
`weights.lisp` の中に `;;` コメント行として残ります（リーダは無視します）。

参考字形（`prototypes.lisp` / `glyphs.js` / `samples/`）はコミット済みの生成物です。
フォント・解像度・対象文字を変えたときだけ、再生成してから `gen.sh` を実行します。

```bash
examples/hiragana/regen-glyphs.sh   # glyphgen/GlyphGen.java を実行して字形を再生成
```

再生成には設定フォント（macOS の Hiragino Maru Gothic ProN・Klee・YuGothic・Hiragino
Mincho ProN）が入った JDK が必要です。フォントを増減するときは `GlyphGen.java` の `FONTS` を
編集します。

## 実データ (Kuzushiji-49) で学習する — (B) 経路

既定の `gen.sh` は**合成フォント字形**で学習します（こちらが**コミット済み/GitHub Pages の
ライブ**モデル）。参考として、**実データ (Kuzushiji-49, 49 クラス)** で学習した重みを
焼き込む経路も用意してあります。鍵は、推論側（`infer.lisp` → `infer.wasm`）が
**`weights.lisp` 1 ファイルにしか依存しない**ことです。`gen.sh --weights-from FILE` は
その `weights.lisp` を外部生成物に差し替え、rontolisp 側の学習を丸ごとスキップします
（推論は無改造）。

> **(B) は参考用です。** 試した範囲では、焼き込み上限（隠れ層 20）の小 MLP では K49 実データの
> 認識率向上は限定的だったため、ライブには合成 (A) を採用しています。(B) を焼き込みたい場合は
> 下記手順で `infer.wasm` を作り直してください（合成に戻すには引数なしの `gen.sh`）。

rontolisp はバイナリ (`.npz`) を読めず、この規模の学習も非現実的なので、**学習だけ外部
(NumPy)** で行い、完成した重み（Lisp ソース）を渡します。手順とライセンス（CC BY-SA 4.0）は
[`tools/k49/README.md`](tools/k49/README.md) を参照。

```bash
python3 examples/hiragana/tools/k49/train_k49.py                 # 実データ学習 -> weights-k49.lisp
examples/hiragana/gen.sh --weights-from examples/hiragana/weights-k49.lisp  # 焼き込み -> infer.wasm
```

- `weights-k49.lisp` は `*weights*` に加え **49 クラスの `*labels*`** を定義します。
  `infer.lisp` は `defvar` で 46 を既定束縛しますが、load で先に来るこの定義が
  冪等性により 49 を上書きします（既定パスは `*labels*` を出さないので 46 のまま）。
- ブラウザ表示用に `glyphs.js` の `KANA` へ K49 固有の 3 クラス
  （ゐ=wi・ゑ=we・繰り返し記号 ゝ=iter）を追加済みです。参考字形サムネイルは合成 46 のまま。
- ネットは 576-20-49 に保つので JVM 焼き込み上限を満たし、**4 バックエンドすべてで一致**します。
- **精度の正直な但し書き**: 焼き込み上限のため隠れ層 20 と小さく、K49 は崩し字
  （現代の手書き入力とは分布が異なる）なので balanced accuracy は小 MLP の上限
  （概ね 0.5 前後）にとどまります。`--hidden` を増やせば上がりますが WASM/インタプリタ専用に
  なり JVM 推論は外れます（焼き込み上限）。本質的な改善には CNN が必要で、それはこのデモの枠外です。

## ブラウザで動かす

`fetch` で `.wasm` を読むため `http://` で配信してください。

```bash
python3 -m http.server 8000 --directory examples/hiragana
# ブラウザで http://localhost:8000/ を開く
```

マスに字を書くと、書くそばから**リアルタイムで**予測クラスとスコアが更新されます
（ストロークごとに自動認識。「認識」ボタンは手動での再実行用）。下段の参考字形に
寄せて書くとよく当たります。

**ブラウザ要件**: WebAssembly GC 対応（Chrome/Edge 119+, Firefox 120+, Safari 18.2+）。

## ブラウザなしで試す（インタプリタ / JVM）

canvas はブラウザ専用ですが、認識ロジックはバックエンド非依存です。`samples/` に各クラスを
平坦化したビットマップ（`(0.0 1.0 ...)` 1 行）を置いてあります。

`infer.lisp` の `(load ...)` はファイル相対で解決されるので、どのディレクトリから
実行しても `common.lisp` / `weights.lisp` を見つけます（JVM だけはクラス名が出力ファイル名に
なるため、パスを含めないようディレクトリ内で実行します）。

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
| `glyphgen/GlyphGen.java`       | 字形レンダラ（実フォント → 24x24 二値化。`prototypes.lisp` / `glyphs.js` / `samples/` を生成） |
| `regen-glyphs.sh`              | `GlyphGen.java` を実行する薄いラッパ                        |
| `prototypes.lisp`              | 各クラスの 24x24 字形テンプレート（生成物・学習データの種）   |
| `glyphs.js`                    | ブラウザ表示用の参考字形 `GLYPHS`/`KANA`/`ORDER`（生成物）    |
| `train.lisp`                   | オフライン学習本体（`common.lisp`+`prototypes.lisp` を load・データ拡張・SGD・重みのシリアライズ） |
| `infer.lisp`                   | 推論本体（`common.lisp`+`weights.lisp` を load・stdin から読み、forward、クラスを出力） |
| `gen.sh`                       | 学習 → `infer.wasm` 生成のパイプライン（`--weights-from` で (B) 経路） |
| `tools/k49/`                   | 実データ (Kuzushiji-49) 外部学習ツール（(B) 経路・ビルド対象外） |
| `index.html`                   | canvas で描いて認識するブラウザページ                       |
| `wasi-shim.js`                 | WASI Preview1 シム（`../wasm-browser/` と同一のコピー）       |
| `samples/*.txt`                | ブラウザなし確認用の平坦化ビットマップ（全 46 クラス・生成物） |
| `infer.wasm`                   | 学習済み重みを焼き込んだ推論モジュール（生成物・コミット対象） |
| `weights.lisp`                 | 学習で生成される重み（`gen.sh` が出力・`infer.lisp` が load） |

## 限界

- 24x24・46 クラスの小さな MLP です。複数フォント＋アフィン水増しで字形のばらつきにかなり
  耐えますが（書体差・太さ・位置・回転/せん断）、合成データ由来である以上、**大きく崩れた字・
  続け字・画がばらばらに離れた字**は外れます。参考字形に寄せて書くほどよく当たります。
- 字形が似たかな（は/ほ/ま、ね/れ/わ、る/ろ、さ/き など）は依然**混同しやすい**です。
- 精度を上げる残りのレバー（未実装）: 非剛体（elastic）な変形の水増し、さらなる高解像度、
  実データセットでの学習、隠れ層の拡大（ただし JVM の制約に注意）。詳細は
  `.todo/16-extend-hiragana-to-full-set.md`。
- ネットワーク規模は **JVM バックエンドの制約で頭打ち**です（焼き込む浮動小数定数 ≲ 1.3 万、
  上記「重みの焼き込み」参照）。`*hidden*` をこれ以上増やすと JVM 版がロードできなくなります
  （WASM 版だけなら可）。
- ファイル I/O はブラウザのシムで未対応のため、入力は stdin 経由のみです。
