;; The handle-side pushback of unread-char: ONE character for ONE stream at a
;; time, the same shape the Gray protocol's own default method keeps for an
;; instance (gray.lisp's *gray-unread-stream* / *gray-unread-char*) and exactly
;; what CL promises for unread-char. No backend can un-read a file descriptor,
;; a socket or a string input stream, so the character is parked here and the
;; character-reading built-ins consult the cell before touching the stream:
;; eval/UnreadCharLibrary rewrites read-char / read-char-no-hang / peek-char /
;; read-line / unread-char call sites onto the defuns below whenever the
;; program uses unread-char at all. A program that never does is untouched.
;;
;; The interpreter answers the same contract in Java (Environment's read-char /
;; %peek-char / read-line / unread-char definitions), because its built-ins are
;; functions rather than call sites a pre-pass could rewrite.
;;
;; What the cell does NOT reach, identically on all four backends: read-byte,
;; read-sequence and read. A character pushed back before a BYTE read has no
;; meaning, and the other two expand into their loops long after this pass.

(defvar rontolisp::*unread-stream* nil)

(defvar rontolisp::*unread-char* nil)

;; The stream KEY. An omitted stream and the nil designator both mean standard
;; input, which the t designator names, so the three compare equal; every other
;; designator is its own handle and compares with eql.
(defun rontolisp::%unread-key (stream) (if stream stream t))

(defun rontolisp::%unread-char-push (character stream)
  (if rontolisp::*unread-stream*
      (error "UNREAD-CHAR without an intervening READ-CHAR")
      (progn
        (setq rontolisp::*unread-char* character)
        (setq rontolisp::*unread-stream* (rontolisp::%unread-key stream))
        nil)))

;; The parked character of STREAM, draining the cell -- nil when the cell is
;; empty or holds another stream's character.
(defun rontolisp::%unread-char-take (stream)
  (if (eql rontolisp::*unread-stream* (rontolisp::%unread-key stream))
      (let ((c rontolisp::*unread-char*))
        (setq rontolisp::*unread-stream* nil)
        (setq rontolisp::*unread-char* nil)
        c)
      nil))

(defun rontolisp::%unread-read-char (stream eof-error-p eof-value)
  (let ((c (rontolisp::%unread-char-take stream)))
    (if c c (read-char stream eof-error-p eof-value))))

;; "Does this character satisfy PEEK-TYPE?", answered by the built-in peek-char
;; itself over a one-character string input stream rather than by yet another
;; copy of the whitespace set: t stops at the first non-whitespace character
;; and a character stops at itself, so the probe answers nil exactly when the
;; character would have been skipped.
(defun rontolisp::%unread-peek-stops-p (peek-type character)
  (if (eq peek-type t)
      (if (peek-char t (make-string-input-stream (string character)) nil nil)
          t
          nil)
      (char= character peek-type)))

(defun rontolisp::%unread-peek-char (peek-type stream eof-error-p eof-value)
  (let ((c (rontolisp::%unread-char-take stream)))
    (if c
        (if (if peek-type (rontolisp::%unread-peek-stops-p peek-type c) t)
            ;; The character stopped on stays in the stream (CL 21.2), so it
            ;; goes straight back into the cell.
            (progn
              (rontolisp::%unread-char-push c stream)
              c)
            (peek-char peek-type stream eof-error-p eof-value))
        (peek-char peek-type stream eof-error-p eof-value))))

;; read-line DRAINS the cell rather than signalling: peek-char is defined as a
;; read plus an unread, so a pushed-back character before a line read is an
;; ordinary shape, and answering the line without it would be silently short by
;; one character. A pushed-back newline ends the line right there.
(defun rontolisp::%unread-read-line (stream eof-error-p eof-value)
  (let ((c (rontolisp::%unread-char-take stream)))
    (if c
        (if (char= c #\Newline)
            ""
            (let ((rest (read-line stream nil nil)))
              (if rest (concatenate 'string (string c) rest) (string c))))
        (read-line stream eof-error-p eof-value))))
