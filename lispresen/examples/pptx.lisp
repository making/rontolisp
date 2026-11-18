;;;; pptx.lisp -- the golden input of the PPTX leg of check.sh: one slide of every kind
;;;; lispresen exports. Its images and code are this directory's.

(deck :title "PPTX"
      :author "lispresen"
      :date "2026-11-18"
      :logo (image "logo.svg" :alt "lispresen")
      :url "https://example.com/pptx"
      :page-numbers t)

(section "ブロック")

(slide :title "文とリスト"
  (ul "一つ目"
      (li "二つ目" (ul "入れ子")))
  (p "本文 " (b "太字") " と " (i "斜体") " と " (tt "コード") " と "
     (math (= x 1)) "。"))

(slide :title "コード"
  (code :lang "lisp" "(defun square (x) (* x x))
(map square '(1 2 3))"))

(slide :title "表"
  (table :header ("名前" "値") :caption "数値は右に揃う"
    ("a" "1") ("b" "2")))

(slide :title "図"
  (flow "read" "parse" "emit"))

(slide :title "グラフ"
  (bars :unit " 件" ("a" 1) ("b" 2)))

(slide :title "円グラフ"
  (pie :donut t :unit "%" ("a" 60) ("b" 40)))

(slide :title "数式"
  (math (= x (/ (* -1 b) (* 2 a)))))

(slide :title "画像"
  (image "pipeline.png" :alt "パイプライン"))

(slide :title "注釈"
  (say "注" (p "対象の段落")))

(slide :title "ノート"
  (p "話者ノートのあるスライド")
  (notes "話者ノート"))

(slide :title "動画"
  (video "demo.mp4" :alt "デモ" :poster "demo-poster.png"))

(slide :title "ページ"
  (frame "https://example.com" :alt "example"))

(slide :title "引用"
  (quote :cite "出典" "引用した文")
  (source "出典: lispresen"))

(slide :title "分割" :layout :image-right
  (ul "左に本文")
  (image "demo-poster.png" :alt "ポスター" :share 1/3))

(slide (image "demo-poster.png" :alt "全面"))

(slide "一文だけのスライド。")
