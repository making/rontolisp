;;;; A lispresen deck. `lispresen talk.lisp` writes talk.html; open it in a browser.
;;;; Every form and option is described in the README:
;;;; https://github.com/making/rontolisp/blob/develop/lispresen/README.md

;; The header. :title adds a title slide.
(deck :title "タイトル"
      :author "名前"
      :date "YYYY-MM-DD"
      :description "このデッキを一文で説明する"
      ;; :theme :dark                  ; :light, :dark, :high-contrast or :midnight
      ;; :url "https://example.com/"   ; a QR code of the URL on the title slide
      ;; A link preview of the published page, when it is shared:
      ;; :page-url "https://example.com/talk.html"
      ;; :page-image "talk.png"        ; the title slide drawn as a PNG beside the HTML
      :page-numbers t)

;; A section divider.
(section "はじめに")

;; A slide: blocks from top to bottom. A string is a paragraph; a list item may hold a
;; nested list. Notes show only in the presenter window (press P).
(slide :title "箇条書き"
  "文字の大きさと配置は lispresen が決める。"
  (ul "一つ目の項目"
      (li "二つ目の項目" (ul "入れ子の項目")))
  (notes "話すことのメモ。"))

;; A slide holding one sentence fills the screen.
(slide "一文だけのスライドは大きく表示される。")

(section "本題")

;; Inline forms: b, i, tt, mark, accent and link.
(slide :title "文字の装飾"
  (p (b "太字") "、" (i "斜体") "、" (tt "code") "、" (mark "強調") "、" (accent "アクセント"))
  (p (link "https://github.com/making/rontolisp" "リンク")))

;; Lisp, Scheme and Clojure code runs in the deck: click it, then Ctrl+Enter.
;; :run nil only shows it; :lang "console" is a shell transcript.
(slide :title "コード"
  (code :lang "lisp" "
    (defun fib (n)
      (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
    (fib 20)"))

;; Columns stand side by side; say puts a comment in a bubble pointing at its content.
(slide :title "二つの列"
  (column (p (b "左の列")) (ul "項目 A" (say "吹き出しで補足する" "項目 B")))
  (column (p (b "右の列")) (ul "項目 C" "項目 D")))

;; A table, and a diagram of boxes joined by arrows.
(slide :title "表"
  (table :header ("項目" "時間")
    ("発表" "20 分")
    ("質疑" "10 分")))

(slide :title "図"
  (flow "書く" "描画する" "発表する"))

;; An image is a file beside the deck, fitted into the slide:
;; (slide :title "画像" (image "photo.png" :alt "写真の説明" :caption "キャプション"))

(section "まとめ")

(slide :title "まとめ"
  (ol "要点の一つ目" "要点の二つ目"))
