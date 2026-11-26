;;;; Tower of Hanoi in rontolisp
;;;; Classic recursive puzzle solver: move N disks from source to destination
;;;; using an auxiliary peg, printing each move. Demonstrates recursion,
;;;; conditional logic, and list accumulation.
;;;; Runs on all three backends (interpreter / JVM / WASM).
;;;;
;;;; Run:
;;;;   rontolisp examples/console/hanoi.lisp
;;;;   rontolisp examples/console/hanoi.lisp -o Hanoi.class && java Hanoi
;;;;   rontolisp examples/console/hanoi.lisp -o hanoi.wasm && wasmtime run hanoi.wasm

(defun take (n lst)
  "Return the first N elements of LST."
  (if (or (<= n 0) (null lst)) nil (cons (car lst) (take (1- n) (cdr lst)))))

(defun count-moves (n)
  "Return the number of moves for N disks: 2^n - 1."
  (1- (expt 2 n)))

(defun hanoi (n source destination auxiliary)
  "Move N disks from SOURCE to DESTINATION via AUXILIARY, printing each move."
  (if (= n 1)
      (format t "  Move disk 1 from ~a to ~a~%" source destination)
      (progn
        (hanoi (1- n) source auxiliary destination)
        (format t "  Move disk ~d from ~a to ~a~%" n source destination)
        (hanoi (1- n) auxiliary destination source))))

(defun hanoi-moves (n source destination auxiliary)
  "Move N disks, returning a list of (from to) pairs instead of printing."
  (if (= n 1)
      (list (list source destination))
      (append (hanoi-moves (1- n) source auxiliary destination)
              (list (list source destination))
              (hanoi-moves (1- n) auxiliary destination source))))

(format t "Tower of Hanoi (3 disks):~%")
(hanoi 3 "A" "B" "C")
(format t "~%Total moves: ~d (expected: ~d = 2^3 - 1)~%"
        (length (hanoi-moves 3 "A" "B" "C")) (count-moves 3))

(format t "~%Tower of Hanoi (4 disks) — first 5 moves:~%")
(let ((move-list (hanoi-moves 4 "α" "β" "γ")))
  (format t "Total: ~d moves (expected: ~d)~%" (length move-list)
          (count-moves 4))
  (dolist (m (take 5 move-list)) (format t "  ~a -> ~a~%" (car m) (cadr m)))
  (when (> (length move-list) 5)
    (format t "  ... (~d more)~%" (- (length move-list) 5))))
