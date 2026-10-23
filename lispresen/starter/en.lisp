;;;; A lispresen deck. `lispresen talk.lisp` writes talk.html; open it in a browser.
;;;; Every form and option is described in the README:
;;;; https://github.com/making/rontolisp/blob/develop/lispresen/README.md

;; The header. :title adds a title slide.
(deck :title "Title"
      :author "Name"
      :date "YYYY-MM-DD"
      :description "What this deck is about, in one sentence"
      :lang "en"
      ;; :theme :dark                  ; :light, :dark, :high-contrast or :midnight
      ;; :url "https://example.com/"   ; a QR code of the URL on the title slide
      ;; A link preview of the published page, when it is shared:
      ;; :page-url "https://example.com/talk.html"
      ;; :page-image "talk.png"        ; the title slide drawn as a PNG beside the HTML
      :page-numbers t)

;; A section divider.
(section "Introduction")

;; A slide: blocks from top to bottom. A string is a paragraph; a list item may hold a
;; nested list. Notes show only in the presenter window (press P).
(slide :title "Lists"
  "lispresen chooses the font sizes and the placement."
  (ul "The first item"
      (li "The second item" (ul "A nested item")))
  (notes "What to say on this slide."))

;; A slide holding one sentence fills the screen.
(slide "A slide of one sentence is shown large.")

(section "The topic")

;; Inline forms: b, i, tt, mark, accent and link.
(slide :title "Inline forms"
  (p (b "bold") ", " (i "italic") ", " (tt "code") ", " (mark "marked") ", " (accent "accent"))
  (p (link "https://github.com/making/rontolisp" "a link")))

;; Lisp, Scheme and Clojure code runs in the deck: click it, then Ctrl+Enter.
;; :run nil only shows it; :lang "console" is a shell transcript.
(slide :title "Code"
  (code :lang "lisp" "
    (defun fib (n)
      (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
    (fib 20)"))

;; Columns stand side by side; say puts a comment in a bubble pointing at its content.
(slide :title "Two columns"
  (column (p (b "Left")) (ul "Item A" (say "A comment in a bubble" "Item B")))
  (column (p (b "Right")) (ul "Item C" "Item D")))

;; A table, and a diagram of boxes joined by arrows.
(slide :title "A table"
  (table :header ("Part" "Time")
    ("Talk" "20 min")
    ("Questions" "10 min")))

(slide :title "A diagram"
  (flow "Write" "Render" "Present"))

;; An image is a file beside the deck, fitted into the slide:
;; (slide :title "An image" (image "photo.png" :alt "What it shows" :caption "A caption"))
;; Alone on a slide without a title it fills the slide; :layout :image-left (or
;; :image-right) sets it edge to edge beside the text:
;; (slide (image "photo.png" :alt "What it shows"))
;; (slide :title "An image" :layout :image-left (image "photo.png" :alt "...") (ul "a" "b"))

(section "Summary")

(slide :title "Summary"
  (ol "The first point" "The second point"))
