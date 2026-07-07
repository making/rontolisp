;; dice.lisp -- rolls a handful of dice using `random`.
;; On WASI Preview 1 `random` draws real entropy from the host's `random_get`
;; (the JavaScript shim backs it with `crypto.getRandomValues`), so every click
;; produces a different roll -- it is NOT a fixed pseudo-random sequence.

(defun roll () (+ 1 (random 6)))

(let ((total 0))
  (format t "Rolling five six-sided dice:~%")
  (dotimes (i 5)
    (let ((r (roll)))
      (setq total (+ total r))
      (format t "  die ~a -> ~a~%" (+ i 1) r)))
  (format t "~%")
  (format t "Total: ~a~%" total))
