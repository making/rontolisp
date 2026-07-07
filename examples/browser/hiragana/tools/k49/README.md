# 実データ学習 (Kuzushiji-49) — デモの (B) 経路

手書きひらがなデモの推論は `weights.lisp` という1ファイルだけに依存しています。
ここはその `weights.lisp` を**合成テンプレートではなく実データ（Kuzushiji-49）から**
作る外部ツールです。出力した重みを `gen.sh --weights-from` に渡すと、推論側
（`infer.lisp` → `infer.wasm`）は無改造のまま実データ学習モデルに差し替わります。

rontolisp はバイナリ（`.npz`）を読めず、この規模の学習も非現実的なので、**学習は
NumPy で外部実行**し、rontolisp には完成した重み（Lisp ソース）だけを渡します。
**推論は従来どおり rontolisp → WASM** で動きます（このデモの主眼）。

## 必要なもの

- Python 3 + NumPy + Pillow（`pip install numpy pillow`）
- 初回実行時に Kuzushiji-49 の `.npz`（約80MB）を `tools/k49/data/` に自動ダウンロード
  （コミット対象外）。

## 使い方

```bash
# 1) 実データで学習し、weights-k49.lisp を生成（examples/browser/hiragana/ 直下に出力）
python3 examples/browser/hiragana/tools/k49/train_k49.py

# 2) その重みを焼き込んで infer.wasm を生成（gen.sh の後半だけが走る）
examples/browser/hiragana/gen.sh --weights-from examples/browser/hiragana/weights-k49.lisp

# 3) ブラウザ or 各バックエンドで確認（既定デモと同じ手順）
python3 -m http.server 8000 --directory examples/browser/hiragana
```

主なオプション:

```bash
python3 train_k49.py --hidden 20      # 隠れ層（既定20: JVM 焼き込み上限を満たす）
                     --epochs 60
                     --per-class-cap 3000  # クラス毎の最大枚数（0で全件。不均衡データの均衡化）
                     --out ../../weights-k49.lisp
```

## 出力フォーマット

`weights-k49.lisp` は推論側の契約（`common.lisp` / `infer.lisp`）に厳密に一致:

```lisp
(defparameter *labels* (list "a" "i" ... "wi" "we" "wo" "n" "iter"))  ; 49クラス
(defparameter *weights* (list
  (list 20 576 (list <W1 を row-major で平坦化>) (list <b1>))   ; 入力→隠れ
  (list 49 20  (list <W2 を row-major で平坦化>) (list <b2>)))) ; 隠れ→出力
```

- ネットは `sigmoid(W1 x + b1) → sigmoid(W2 a1 + b2)` の argmax。`common.lisp` の
  forward と同一なので、焼き込んだ重みは学習時の予測を完全に再現します。
- `*labels*` は **K49 のクラス順 0..48**。`infer.lisp` は `defvar` で 46 を既定束縛
  しますが、この `defparameter`（load で先に来る）が冪等性により 49 を上書きします。
- ブラウザの `glyphs.js` には `wi`/`we`/`iter`(ゐ/ゑ/ゝ) の表示用 KANA を追加済みなので、
  49 クラスの予測もかなで表示されます（参考字形サムネイルは合成 46 のまま）。

## クラス対応（K49 → デモ）

K49 のクラス 0–43（あ〜わ）はデモの index 0–43 と完全一致。44=ゐ(wi)・45=ゑ(we)・
46=を(wo)・47=ん(n)・48=ゝ(iter)。デモの 46 は K49 から {wi, we, iter} を除いた集合です。

## 精度の見込み（正直な但し書き）

JVM 焼き込み上限（浮動小数 ≲ 1.3万）を保つため隠れ層は既定 20 と小さく、K49 は難しい
データセットなので、balanced accuracy は小 MLP の上限（概ね中程度）にとどまります。
それでも合成テンプレートの暗記とは違い、**実際の手書き分布**を学ぶので崩し字への耐性は
上がります。さらに上を狙うなら `--hidden` を増やす（WASM/インタプリタ専用になり JVM 推論は
不可）か、CNN（このデモの焼き込み枠を超える）が必要です。

## ライセンス / 帰属

Kuzushiji-49 は ROIS-DS 人文学オープンデータ共同利用センター (CODH) 提供、
**CC BY-SA 4.0**（要帰属）。`.npz` は再配布せず初回に取得するだけです。本ツールが出力する
重みはデータからの派生物にあたるため、`weights-k49.lisp` を配布する場合は同ライセンス下で
帰属表示を伴ってください。

> "KMNIST Dataset" (created by CODH), adapted from "Kuzushiji Dataset" (created
> by NIJL and others), doi:10.20676/00000341
