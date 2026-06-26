;;;; Tabular Q-learning that solves a grid maze, in rontolisp.
;;;;
;;;; An agent learns to walk from S to G through a maze of walls (#) by trial
;;;; and error.  The action-value function Q(state, action) is stored in a
;;;; hash table keyed by (row col action) with the standard `equal` test, which
;;;; is the idiomatic Common Lisp representation for a sparse Q-table.
;;;;
;;;; The run is fully deterministic on every backend: randomness comes from a
;;;; small self-contained LCG (not the built-in `random`, whose source differs
;;;; between the interpreter/JVM and WASM), and floating-point updates use only
;;;; +, -, *, / so the interpreter, JVM and WASM all print the same result.
;;;;
;;;; To stay within rontolisp's compilers, mutable state (the Q-table and the
;;;; RNG seed) is threaded through as arguments rather than held in globals that
;;;; functions assign to; the hash table is mutated in place via `setf gethash`.

;;; ---------------------------------------------------------------------------
;;; Deterministic RNG: a ZX-Spectrum-style LCG whose state is a one-element list
;;; so it can be mutated in place. All intermediate values stay well within the
;;; WASM i31 integer range (max product 75 * 65536).
;;; ---------------------------------------------------------------------------

(defun rng-next (rng)                       ; -> integer in [0, 65537)
  (let ((s (mod (+ (* (car rng) 75) 74) 65537)))
    (setf (car rng) s)
    s))

(defun rng-float (rng)                      ; -> float in [0, 1)
  (/ (float (rng-next rng)) 65537.0))

(defun rng-below (rng n)                    ; -> integer in [0, n)
  (mod (rng-next rng) n))

;;; ---------------------------------------------------------------------------
;;; Maze: a list of equal-length strings.  # is a wall, S the start, G the goal,
;;; . an open cell. A state is the list (row col).
;;; ---------------------------------------------------------------------------

(defun maze-rows (maze) (length maze))
(defun maze-cols (maze) (length (first maze)))
(defun maze-ref (maze r c) (char (nth r maze) c))

(defun wall-p (maze r c)
  (or (< r 0) (< c 0)
      (>= r (maze-rows maze)) (>= c (maze-cols maze))
      (char= (maze-ref maze r c) #\#)))

;; Scan the grid for the first cell holding CH, returning (row col) or nil.
(defun find-cell (maze ch)
  (let ((r 0) (rows (maze-rows maze)) (found nil))
    (while (and (< r rows) (null found))
      (let ((c 0) (cols (maze-cols maze)))
        (while (and (< c cols) (null found))
          (when (char= (maze-ref maze r c) ch)
            (setq found (list r c)))
          (setq c (+ c 1))))
      (setq r (+ r 1)))
    found))

;;; ---------------------------------------------------------------------------
;;; Actions: 0 up, 1 down, 2 left, 3 right.
;;; ---------------------------------------------------------------------------

(defun move (r c a)
  (cond ((= a 0) (list (- r 1) c))
        ((= a 1) (list (+ r 1) c))
        ((= a 2) (list r (- c 1)))
        (t       (list r (+ c 1)))))

;;; ---------------------------------------------------------------------------
;;; Q-table: a hash table keyed by (row col action), default value 0.0.
;;; ---------------------------------------------------------------------------

(defun q-get (q r c a) (gethash (list r c a) q 0.0))
(defun q-set (q r c a v) (setf (gethash (list r c a) q) v))

(defun max-q (q r c)                        ; best action-value at (r c)
  (let ((best (q-get q r c 0)) (a 1))
    (while (< a 4)
      (let ((v (q-get q r c a)))
        (when (> v best) (setq best v)))
      (setq a (+ a 1)))
    best))

(defun best-action (q r c)                  ; argmax action (ties -> lowest index)
  (let ((ba 0) (bv (q-get q r c 0)) (a 1))
    (while (< a 4)
      (let ((v (q-get q r c a)))
        (when (> v bv) (setq bv v) (setq ba a)))
      (setq a (+ a 1)))
    ba))

(defun choose-action (q rng r c epsilon)    ; epsilon-greedy
  (if (< (rng-float rng) epsilon)
      (rng-below rng 4)
      (best-action q r c)))

;;; ---------------------------------------------------------------------------
;;; Hyper-parameters travel together as a list (alpha gamma epsilon max-steps),
;;; which keeps each function's arity small (rontolisp's WASM backend allows up
;;; to seven parameters) and reads more clearly than a long positional list.
;;; ---------------------------------------------------------------------------

(defun hp-alpha (hp) (first hp))
(defun hp-gamma (hp) (second hp))
(defun hp-epsilon (hp) (third hp))
(defun hp-max-steps (hp) (fourth hp))

;;; ---------------------------------------------------------------------------
;;; One episode of Q-learning from the start cell, returning the step count.
;;; Q(s,a) <- Q(s,a) + alpha * (reward + gamma * max_a' Q(s',a') - Q(s,a))
;;; ---------------------------------------------------------------------------

(defun run-episode (maze q rng start goal hp)
  (let ((r (first start)) (c (second start)) (steps 0) (done nil)
        (alpha (hp-alpha hp)) (gamma (hp-gamma hp))
        (epsilon (hp-epsilon hp)) (max-steps (hp-max-steps hp)))
    (while (and (< steps max-steps) (not done))
      (let* ((a (choose-action q rng r c epsilon))
             (nxt (move r c a))
             (nr (first nxt))
             (nc (second nxt)))
        (when (wall-p maze nr nc)           ; blocked: stay put
          (setq nr r)
          (setq nc c))
        (let* ((at-goal (and (= nr (first goal)) (= nc (second goal))))
               (reward (if at-goal 10.0 -0.1))
               (old (q-get q r c a))
               (future (if at-goal 0.0 (max-q q nr nc)))
               (target (+ reward (* gamma future)))
               (updated (+ old (* alpha (- target old)))))
          (q-set q r c a updated)
          (setq r nr)
          (setq c nc)
          (setq steps (+ steps 1))
          (when at-goal (setq done t)))))
    steps))

(defun train (maze q rng start goal hp episodes)
  (let ((e 0))
    (while (< e episodes)
      (run-episode maze q rng start goal hp)
      (setq e (+ e 1)))))

;;; ---------------------------------------------------------------------------
;;; Follow the greedy policy from start to goal, collecting the visited cells.
;;; ---------------------------------------------------------------------------

(defun greedy-path (maze q start goal max-steps)
  (let ((r (first start)) (c (second start)) (steps 0)
        (done nil) (cells (list start)))
    (while (and (< steps max-steps) (not done))
      (if (and (= r (first goal)) (= c (second goal)))
          (setq done t)
          (let* ((a (best-action q r c))
                 (nxt (move r c a))
                 (nr (first nxt))
                 (nc (second nxt)))
            (when (wall-p maze nr nc)
              (setq nr r)
              (setq nc c))
            (setq r nr)
            (setq c nc)
            (setq cells (cons (list r c) cells))
            (setq steps (+ steps 1)))))
    (reverse cells)))

;;; ---------------------------------------------------------------------------
;;; Render the maze with the learned path drawn as o.
;;; ---------------------------------------------------------------------------

(defun print-solution (maze path)
  (let ((r 0) (rows (maze-rows maze)))
    (while (< r rows)
      (let ((c 0) (cols (maze-cols maze)))
        (while (< c cols)
          (let ((ch (maze-ref maze r c)))
            (cond ((char= ch #\#) (princ "#"))
                  ((char= ch #\S) (princ "S"))
                  ((char= ch #\G) (princ "G"))
                  ((member (list r c) path :test #'equal) (princ "o"))
                  (t (princ "."))))
          (setq c (+ c 1))))
      (terpri)
      (setq r (+ r 1)))))

;;; ---------------------------------------------------------------------------
;;; Run
;;; ---------------------------------------------------------------------------

(defparameter *maze*
  (list "#########"
        "#S..#...#"
        "#.#.#.#.#"
        "#.#...#.#"
        "#.#####.#"
        "#.....#G#"
        "#.#####.#"
        "#.......#"
        "#########"))

(defparameter *q* (make-hash-table :test 'equal))
(defparameter *rng* (list 1))               ; LCG seed
(defparameter *start* (find-cell *maze* #\S))
(defparameter *goal* (find-cell *maze* #\G))
(defparameter *hp* (list 0.5 0.9 0.2 300))  ; alpha gamma epsilon max-steps

(format t "Maze ~a x ~a, training tabular Q-learning...~%"
        (maze-rows *maze*) (maze-cols *maze*))

(train *maze* *q* *rng* *start* *goal* *hp* 500)

(defparameter *path* (greedy-path *maze* *q* *start* *goal* 100))

(format t "Learned ~a state-action values~%" (hash-table-count *q*))
(format t "Greedy path length: ~a steps~%" (- (length *path*) 1))
(print-solution *maze* *path*)
