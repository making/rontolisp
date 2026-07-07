;;;; minesweeper-core.lisp -- Minesweeper rules, with no rendering.
;;;;
;;;; This is the shared game logic behind BOTH front-ends: the browser/WASM one
;;;; (minesweeper-wasm.lisp, which adds HTML rendering and host-callable exports)
;;;; and the desktop/Swing one (minesweeper-swing.lisp, which paints a Swing grid).
;;;; Only the drawing differs between them; the rules below are identical.
;;;;
;;;; The game is a pure state machine: every action takes the current state and
;;;; returns the next one. Nothing here touches the screen, entropy, or the host.
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
;;;; The mine layout is supplied by the caller (the browser uses JavaScript's RNG;
;;;; the Swing front-end uses the interpreter's `random`), which lets each host
;;;; keep the mines away from the very first click.
;;;;
;;;; This uses cons/list operations, so it needs a GC backend
;;;; (interpreter / JVM / WASM-GC); it is NOT in the --no-gc subset.

;;; --- small list helpers ------------------------------------------------------

;;; Destructively set element I of LST to VAL, returning LST. The lists we mutate
;;; are freshly built for each game (or parsed from the :s-expr argument on every
;;; host call), so this never leaks state between actions.
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

;;; A w*h mine bit-list with COUNT mines, placed at the first COUNT cells of
;;; ORDER (a host-supplied ordering of cell indices, normally a random
;;; permutation of 0..w*h-1) that are neither the safe first-click cell SAFE nor
;;; one of its neighbours -- so the opening move is always safe. This is the
;;; shared placement RULE; the host only supplies the random ORDER, because the
;;; entropy-free --no-wasi WASM reactor cannot call `random` (the browser shuffles
;;; in JavaScript, the Swing front-end uses the interpreter's `random`).
(defun place-mines (w h count safe order)
  (let ((mines (zeros (* w h)))
        (forbidden (cons safe (neighbors safe w h)))
        (placed 0))
    (dolist (idx order)
      (when (and (< placed count)
                 (not (member idx forbidden)))
        (set-nth mines idx 1)
        (setq placed (1+ placed))))
    mines))

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
