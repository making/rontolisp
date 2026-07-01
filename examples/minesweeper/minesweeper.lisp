;;;; minesweeper.lisp -- Minesweeper game logic, compiled to a WebAssembly
;;;; reactor (--no-wasi) and driven from the browser (see minesweeper.html).
;;;;
;;;; The whole game is a pure state machine: every export takes the current
;;;; game state (a nested list of integers) and returns the next state, or
;;;; renders a state to HTML. The browser holds the state as an opaque string
;;;; that round-trips through the WASM :sexpr ABI -- it never parses Lisp.
;;;;
;;;; State layout (a list):
;;;;   (status w h mines revealed flags)
;;;;     status   -- 0 playing, 1 won, 2 lost
;;;;     w h      -- board width / height (columns / rows)
;;;;     mines    -- w*h list of 0/1, the hidden truth (1 = mine)
;;;;     revealed -- w*h list of 0/1 (1 = uncovered)
;;;;     flags    -- w*h list of 0/1 (1 = flagged)
;;;;   A cell index is  i = r*w + c  (row-major).
;;;;
;;;; Randomness: a --no-wasi reactor has no entropy source, so the mine layout
;;;; is generated in JavaScript and passed to new-game. This also lets the host
;;;; make the first click safe (no mine under, or next to, the first cell).
;;;;
;;;; This uses cons/list/string operations, so it needs a GC backend
;;;; (interpreter / JVM / WASM-GC); it is NOT in the --no-gc subset.

;;; --- small list helpers ------------------------------------------------------

;;; Destructively set element I of LST to VAL, returning LST. The lists we mutate
;;; are freshly parsed from the :sexpr argument on every call, so this never
;;; leaks state between host calls.
(defun set-nth (lst i val)
  (rplaca (nthcdr i lst) val)
  lst)

;;; A freshly allocated list of N zeros (make-list only fills with nil).
(defun zeros (n)
  (let ((lst nil))
    (dotimes (i n) (push 0 lst))
    lst))

;;; Count the 1s in a 0/1 list.
(defun count-ones (lst)
  (let ((c 0))
    (dolist (x lst) (when (= x 1) (setq c (1+ c))))
    c))

;;; --- board geometry ----------------------------------------------------------

;;; The indices of the (up to eight) neighbours of cell I on a W x H board.
(defun neighbors (i w h)
  (let ((r (floor (/ i w)))
        (c (mod i w))
        (result nil))
    (dolist (dr (list -1 0 1))
      (dolist (dc (list -1 0 1))
        (unless (and (= dr 0) (= dc 0))
          (let ((nr (+ r dr)) (nc (+ c dc)))
            (when (and (>= nr 0) (< nr h) (>= nc 0) (< nc w))
              (push (+ (* nr w) nc) result))))))
    result))

;;; How many of cell I's neighbours are mines.
(defun adjacent-count (mines i w h)
  (let ((count 0))
    (dolist (n (neighbors i w h))
      (when (= (nth n mines) 1)
        (setq count (1+ count))))
    count))

;;; --- state accessors ---------------------------------------------------------

(defun st-status   (state) (nth 0 state))
(defun st-w        (state) (nth 1 state))
(defun st-h        (state) (nth 2 state))
(defun st-mines    (state) (nth 3 state))
(defun st-revealed (state) (nth 4 state))
(defun st-flags    (state) (nth 5 state))

;;; The game is won once every non-mine cell has been revealed.
(defun won-p (state)
  (= (count-ones (st-revealed state))
     (- (* (st-w state) (st-h state)) (count-ones (st-mines state)))))

;;; --- game actions ------------------------------------------------------------

;;; Build the initial state. MINES is a w*h list of 0/1 supplied by the host.
(defun new-game (w h mines)
  (let ((n (* w h)))
    (list 0 w h mines (zeros n) (zeros n))))

;;; Reveal cell IDX. A safe cell floods outward through all connected zero-count
;;; cells (classic Minesweeper auto-open); a mine ends the game.
(defun reveal (state idx)
  (let ((w (st-w state))
        (h (st-h state))
        (mines (st-mines state))
        (revealed (st-revealed state))
        (flags (st-flags state)))
    (when (and (= (st-status state) 0)
               (= (nth idx flags) 0)
               (= (nth idx revealed) 0))
      (if (= (nth idx mines) 1)
          ;; Stepped on a mine: uncover it and lose.
          (progn
            (set-nth revealed idx 1)
            (set-nth state 0 2))
          ;; Safe: iterative flood fill using an explicit stack of indices.
          (let ((stack (list idx)))
            (loop while stack do
              (let ((j (pop stack)))
                (when (and (= (nth j revealed) 0)
                           (= (nth j flags) 0))
                  (set-nth revealed j 1)
                  ;; Only keep spreading out of blank (zero-count) cells.
                  (when (= (adjacent-count mines j w h) 0)
                    (dolist (nb (neighbors j w h))
                      (when (= (nth nb revealed) 0)
                        (push nb stack)))))))
            (when (won-p state)
              (set-nth state 0 1)))))
    state))

;;; Toggle a flag on cell IDX (only while playing and still covered).
(defun toggle-flag (state idx)
  (let ((revealed (st-revealed state))
        (flags (st-flags state)))
    (when (and (= (st-status state) 0)
               (= (nth idx revealed) 0))
      (set-nth flags idx (- 1 (nth idx flags))))
    state))

;;; --- queries for the host ----------------------------------------------------

;;; 0 playing, 1 won, 2 lost.
(defun game-status (state) (st-status state))

;;; Mines minus flags placed -- the "mines remaining" counter.
(defun mines-remaining (state)
  (- (count-ones (st-mines state)) (count-ones (st-flags state))))

;;; --- rendering to HTML -------------------------------------------------------

;;; One <div> for a cell: a class the CSS styles and a data-i the host reads back
;;; on click. HTML attributes use single quotes so the Lisp string needs no
;;; escapes (double quotes would).
(defun cell-div (i cls txt)
  (concatenate 'string
    "<div class='" cls "' data-i='" (princ-to-string i) "'>" txt "</div>"))

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

(rontolisp:wasm-export 'new-game        :params '(:int :int :sexpr) :returns :sexpr)
(rontolisp:wasm-export 'reveal          :params '(:sexpr :int)      :returns :sexpr)
(rontolisp:wasm-export 'toggle-flag     :params '(:sexpr :int)      :returns :sexpr)
(rontolisp:wasm-export 'render          :params '(:sexpr)           :returns :string)
(rontolisp:wasm-export 'game-status     :params '(:sexpr)           :returns :int)
(rontolisp:wasm-export 'mines-remaining :params '(:sexpr)           :returns :int)
