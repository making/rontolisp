;;;; minesweeper-core-test.lisp -- the rules, checked with rove.
;;;;
;;;; minesweeper-core.lisp is a pure state machine: every action takes the
;;;; current state and returns the next one, and nothing in it touches the
;;;; screen, entropy or the host. That is exactly what makes it testable
;;;; head-less -- the two front-ends beside it (the browser/WASM one and the
;;;; Swing one) cannot be, so this file is where the rules are pinned.
;;;;
;;;; rove is loaded with asdf, so pass the directories holding its .asd files
;;;; (rove, dissect and cl-ppcre, all vendored in this repository) with
;;;; --system-path; outside this repository (ql:quickload "rove") fetches the
;;;; same sources. See the Testing guide: doc/en/guides/testing.md
;;;;
;;;; Run:
;;;;   SP=src/test/resources/rove:src/test/resources/dissect:src/test/resources/cl-ppcre
;;;;   rontolisp minesweeper-core-test.lisp --system-path $SP
;;;;   rontolisp minesweeper-core-test.lisp --system-path $SP -o Tests.class && java Tests
;;;;   rontolisp minesweeper-core-test.lisp --system-path $SP -o tests.wasm --optimize && \
;;;;     wasmtime run tests.wasm

(asdf:load-system :rove)
(use-package :rove)
;; rove colors its report for a terminal; a checked pipeline wants plain text.
(setf *enable-colors* nil)

(load "minesweeper-core.lisp")

;;; A 3x3 board whose only mine is the top-left corner:
;;;
;;;   * 1 .        index 0 1 2
;;;   1 1 .              3 4 5
;;;   . . .              6 7 8
;;;
;;; Cells 2, 5, 6, 7 and 8 have no mine next to them, so revealing any of them
;;; floods over the whole board.
(defun corner-mine-board () (list 1 0 0 0 0 0 0 0 0))

;;; A 4x4 board mined at both diagonal corners, where no single click wins.
(defun two-mine-board () (list 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1))

(deftest geometry
  (testing "a corner has three neighbours, an edge five, the middle eight"
    (ok (= (length (neighbors 0 3 3)) 3))
    (ok (= (length (neighbors 1 3 3)) 5))
    (ok (= (length (neighbors 4 3 3)) 8)))
  (testing "the count is of MINED neighbours"
    (let ((mines (corner-mine-board)))
      (ok (= (adjacent-count mines 1 3 3) 1))
      (ok (= (adjacent-count mines 4 3 3) 1))
      (ok (= (adjacent-count mines 8 3 3) 0)))))

(deftest a-fresh-board
  (let ((state (new-game 3 3 (corner-mine-board))))
    (ok (= (game-status state) 0))
    (testing "nothing is uncovered and nothing is flagged yet"
      (ok (= (count-ones (st-revealed state)) 0))
      (ok (= (mines-remaining state) 1)))))

(deftest revealing-a-blank-cell-floods-and-can-win
  ;; Cell 8 has no mine beside it, so the flood runs until it meets the
  ;; numbered cells around the mine -- which here is every remaining cell, so
  ;; the one click also wins the game.
  (let ((state (reveal (new-game 3 3 (corner-mine-board)) 8)))
    (ok (= (count-ones (st-revealed state)) 8))
    (ok (= (nth 0 (st-revealed state)) 0) "the mine itself stays covered")
    (ok (= (game-status state) 1) "every safe cell uncovered wins")))

(deftest revealing-a-numbered-cell-stops-there
  ;; Cell 5 on the 4x4 board touches the corner mine, so it uncovers alone and
  ;; the game is still on.
  (let ((state (reveal (new-game 4 4 (two-mine-board)) 5)))
    (ok (= (count-ones (st-revealed state)) 1))
    (ok (= (game-status state) 0))))

(deftest stepping-on-a-mine-loses
  (let ((state (reveal (new-game 3 3 (corner-mine-board)) 0)))
    (ok (= (game-status state) 2))
    (ok (= (nth 0 (st-revealed state)) 1) "the mine you stepped on is shown")
    (testing "a finished game ignores further moves"
      (reveal state 8)
      (ok (= (count-ones (st-revealed state)) 1)))))

(deftest flagging
  (let ((state (new-game 3 3 (corner-mine-board))))
    (toggle-flag state 0)
    (ok (= (mines-remaining state) 0) "a flag counts against the mine counter")
    (testing "flagging is a toggle"
      (toggle-flag state 0)
      (ok (= (mines-remaining state) 1)))
    (testing "a flagged cell is protected from a click"
      (toggle-flag state 8)
      (reveal state 8)
      (ok (= (count-ones (st-revealed state)) 0)))))

(deftest the-first-click-is-never-a-mine
  ;; The host supplies the random ORDER; the rule that keeps the opening move
  ;; safe lives here. With the identity order every mine would land on the low
  ;; indices, which is precisely where the safe cell and its neighbours are.
  (let* ((order (list 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15))
         (mines (place-mines 4 4 3 5 order)))
    (ok (= (count-ones mines) 3) "exactly as many mines as asked for")
    (ok (= (nth 5 mines) 0) "not the cell that was clicked")
    (testing "and none of its neighbours either"
      (let ((safe t))
        (dolist (n (neighbors 5 4 4))
          (when (= (nth n mines) 1) (setq safe nil)))
        (ok safe)))))

;;; Loading this file runs its suite (rove's file-driven entry point), and the
;;; exit code is the verdict.
(uiop:quit (if (run-suite *package*) 0 1))
