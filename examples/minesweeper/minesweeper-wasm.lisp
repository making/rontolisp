;;;; minesweeper-wasm.lisp -- Minesweeper browser front-end.
;;;;
;;;; This is the WebAssembly rendering layer: it shares all the rules with the
;;;; Swing front-end by loading minesweeper-core.lisp, then adds HTML rendering
;;;; and host-callable exports. Compiled ahead of time to a --no-wasi reactor and
;;;; driven from the browser (see minesweeper.html). A top-level literal `load`
;;;; is inlined at compile time, so the compiler sees the core `defun`s natively.
;;;;
;;;; The browser holds the game state as an opaque string that round-trips through
;;;; the WASM :sexpr ABI -- it never parses Lisp. Randomness: a --no-wasi reactor
;;;; has no entropy source, so the page supplies only a random ordering of cells
;;;; and the shared core `place-mines` applies the (first-click-safe) placement
;;;; rule -- the same rule the Swing front-end uses, entropy the only difference.
;;;;
;;;; See minesweeper-core.lisp for the state layout and the rules.

(load "minesweeper-core.lisp")

;;; --- rendering to HTML -------------------------------------------------------

;;; HTML-escape one character to safe markup; ordinary characters pass through.
;;; Matched by code point so the reader never sees the tricky #\" / #\' literals.
(defun escape-char (ch)
  (let ((c (char-code ch)))
    (cond ((= c 38) "&amp;")    ; &
          ((= c 60) "&lt;")     ; <
          ((= c 62) "&gt;")     ; >
          ((= c 34) "&quot;")   ; "
          ((= c 39) "&#39;")    ; '
          (t (princ-to-string ch)))))

;;; Escape every HTML-special character in a string (& < > " '), so any cell
;;; label is safe to drop into markup. Same helper as rainbow.lisp.
(defun html-escape (s)
  (reduce (lambda (acc piece) (concatenate 'string acc piece))
          (map 'list #'escape-char s) :initial-value ""))

;;; One <div> for a cell: a class the CSS styles and a data-i the host reads back
;;; on click. HTML attributes use single quotes so the Lisp string needs no
;;; escapes (double quotes would); the cell label is HTML-escaped defensively.
(defun cell-div (i cls txt)
  (concatenate 'string
    "<div class='" cls "' data-i='" (princ-to-string i) "'>" (html-escape txt) "</div>"))

;;; The class/label for cell I given the whole state (which decides how covered
;;; cells and mines are shown once the game is over).
(defun cell-html (state i)
  (let* ((status (st-status state))
         (w (st-w state))
         (h (st-h state))
         (mines (st-mines state))
         (over (> status 0))
         (is-mine (= (nth i mines) 1))
         (is-rev  (= (nth i (st-revealed state)) 1))
         (is-flag (= (nth i (st-flags state)) 1)))
    (cond
      ;; The mine you actually stepped on (revealed) -- highlight it.
      ((and is-rev is-mine) (cell-div i "cell mine boom" ""))
      ;; A normally revealed cell: blank, or a neighbour count 1..8.
      (is-rev
       (let ((cnt (adjacent-count mines i w h)))
         (if (= cnt 0)
             (cell-div i "cell open" "")
             (cell-div i (concatenate 'string "cell open n" (princ-to-string cnt))
                       (princ-to-string cnt)))))
      ;; Game over: reveal every remaining mine.
      ((and over is-mine) (cell-div i "cell mine" ""))
      ;; Game over: a flag that turned out to be wrong.
      ((and over is-flag) (cell-div i "cell wrongflag" ""))
      ;; A flag still standing.
      (is-flag (cell-div i "cell flag" ""))
      ;; An ordinary covered cell.
      (t (cell-div i "cell hidden" "")))))

;;; The whole board as a run of cell <div>s (the host wraps them in a CSS grid
;;; sized to the board width).
(defun render (state)
  (let ((n (* (st-w state) (st-h state)))
        (out ""))
    (dotimes (i n)
      (setq out (concatenate 'string out (cell-html state i))))
    out))

;;; --- host-callable exports ---------------------------------------------------

(rontolisp:wasm-export 'place-mines     :params '(:int :int :int :int :sexpr) :returns :sexpr)
(rontolisp:wasm-export 'new-game        :params '(:int :int :sexpr) :returns :sexpr)
(rontolisp:wasm-export 'reveal          :params '(:sexpr :int)      :returns :sexpr)
(rontolisp:wasm-export 'toggle-flag     :params '(:sexpr :int)      :returns :sexpr)
(rontolisp:wasm-export 'render          :params '(:sexpr)           :returns :string)
(rontolisp:wasm-export 'game-status     :params '(:sexpr)           :returns :int)
(rontolisp:wasm-export 'mines-remaining :params '(:sexpr)           :returns :int)
