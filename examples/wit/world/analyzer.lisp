;;;; Implementation of the WIT world 'analyzer' (wit/analyzer.wit).
;;;;
;;;; The world is the contract: the compiler checks every defun below against
;;;; it, so a renamed export, a changed arity or a changed type is a compile
;;;; error rather than a runtime surprise. Fill in the bodies; each one signals
;;;; until you do.
;;;;
;;;; This header, every ;;; doc comment and ";;; WIT:" signature line below, the
;;;; four defun headers and the directive at the bottom were all produced from
;;;; the world alone by
;;;;
;;;;   rontolisp --scaffold-wit wit/analyzer.wit -o analyzer.lisp
;;;;
;;;; The hand-written parts are the helpers and the four bodies, which replaced
;;;; the generated (error "... is not implemented yet") stubs. README.md walks
;;;; through the whole workflow.

;;; ---------------------------------------------------------------------------
;;; Helpers -- ordinary Lisp. The world constrains only the functions it names;
;;; the exports may call anything else in the file.
;;; ---------------------------------------------------------------------------

;;; The words of TEXT: the runs of characters between spaces. Leading, trailing
;;; and repeated spaces produce no empty words.
(defun split-words (text)
  (let ((words '()) (current ""))
    (dotimes (i (length text))
      (let ((c (char text i)))
        (if (char= c #\Space)
            (progn
              (when (> (length current) 0) (setq words (cons current words)))
              (setq current ""))
            (setq current (concatenate 'string current (string c))))))
    (when (> (length current) 0) (setq words (cons current words)))
    (reverse words)))

;;; The letters and digits of TEXT, folded to lower case: what a palindrome test
;;; actually compares.
(defun letters-and-digits (text)
  (let ((out ""))
    (dotimes (i (length text))
      (let ((c (char text i)))
        (when (or (alpha-char-p c) (digit-char-p c))
          (setq out (concatenate 'string out (string (char-downcase c)))))))
    out))

;;; ---------------------------------------------------------------------------
;;; The four exports the world declares.
;;; ---------------------------------------------------------------------------

;;; Count the words in the text. A word is a run of characters separated by
;;; spaces; leading, trailing and repeated spaces do not count.
;;; WIT: word-count: func(text: string) -> s32
(defun word-count (text) (length (split-words text)))

;;; Return the longest word in the text, or the empty string when it has none.
;;; WIT: longest-word: func(text: string) -> string
(defun longest-word (text)
  (let ((best ""))
    (dolist (word (split-words text))
      (when (> (length word) (length best)) (setq best word)))
    best))

;;; Report whether the text reads the same backwards, ignoring letter case and
;;; every character that is not a letter or a digit.
;;; WIT: is-palindrome: func(text: string) -> bool
(defun is-palindrome (text)
  (let ((s (letters-and-digits text))) (string= s (reverse s))))

;;; Print a human-readable report about the text to standard output. It is
;;; declared async because it performs I/O: a synchronous export may not block,
;;; so this is the one property the world has to state rather than let an
;;; implementer guess.
;;; WIT: print-report: async func(text: string)
(defun print-report (text)
  (format t "text:         ~a~%" text)
  (format t "words:        ~a~%" (word-count text))
  (format t "longest word: ~a~%" (longest-word text))
  (format t "palindrome:   ~a~%" (if (is-palindrome text) "yes" "no")))

(rontolisp:wit-export "wit/analyzer.wit" :world analyzer)
