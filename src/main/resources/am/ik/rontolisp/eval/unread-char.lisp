;; The handle-side pushback of unread-char: ONE character per stream, exactly
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
;; file-position counts a parked character as not consumed yet (sbcl): the
;; query answers the offset before it -- one character back on a string input
;; stream, its UTF-8 length on a file stream -- and a set drops it. Those two
;; defuns are spliced only for a program that names file-position itself.
;;
;; What the cell does NOT reach, identically on all four backends: read-byte,
;; read-sequence and read. A character pushed back before a BYTE read has no
;; meaning, and the other two expand into their loops long after this pass.
;;
;; WHERE the character lives: on the stream the designator DENOTES (the KEY,
;; %unread-key), so every designator of one stream reaches one cell. An open
;; stream VALUE carries it in its own reserved cell
;; (LispLayout.STREAM_PUSHBACK_CELL, the literal 3 below), as CL keeps the
;; pushback on the stream -- so a stream closed or dropped with a character
;; parked takes it along, and two streams each hold one. Anything else (the t
;; designator, the process standard input) shares the ONE cell below, keyed by
;; the designator.

(defvar rontolisp::*unread-stream* nil)

(defvar rontolisp::*unread-char* nil)

;; The stream KEY: the stream the designator DENOTES. An omitted stream and the
;; nil designator mean the current *standard-input* -- the stream the read
;; reads -- and a synonym stream the stream its variable holds now,
;; recursively, the resolution %stream-target makes without unwrapping an open
;; stream to its handle. A nil left over is the process standard input, the t
;; designator *standard-input* holds until a program binds it; every other key
;; compares with eql.
(defun rontolisp::%unread-key (stream)
  (let ((s (if stream stream *standard-input*)))
    (if (%obj-is s '%SYNONYM-STREAM)
        (rontolisp::%unread-key (funcall (%obj-ref s 1)))
        (if s s t))))

;; The character parked under KEY, left in place -- nil when none is.
(defun rontolisp::%unread-parked (key)
  (if (%obj-is key '%STREAM)
      (%obj-ref key 3)
      (if (eql rontolisp::*unread-stream* key) rontolisp::*unread-char* nil)))

;; Parks CHARACTER under KEY, or empties its cell when CHARACTER is nil. An
;; empty shared cell is nil in both halves: %unread-key never answers nil, so
;; no live key is ever nil.
(defun rontolisp::%unread-store (key character)
  (if (%obj-is key '%STREAM)
      (%obj-set key 3 character)
      (progn
        (setq rontolisp::*unread-stream* (if character key nil))
        (setq rontolisp::*unread-char* character))))

(defun rontolisp::%unread-char-push (character stream)
  (let ((key (rontolisp::%unread-key stream)))
    (if (if (%obj-is key '%STREAM)
            (rontolisp::%unread-parked key)
            rontolisp::*unread-stream*)
        (error "UNREAD-CHAR without an intervening READ-CHAR")
        (progn
          (rontolisp::%unread-store key character)
          nil))))

;; The parked character of STREAM, draining its cell -- nil when none is.
(defun rontolisp::%unread-char-take (stream)
  (let* ((key (rontolisp::%unread-key stream))
         (c (rontolisp::%unread-parked key)))
    (if c (rontolisp::%unread-store key nil))
    c))

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
  ;; The line's missing-newline-p rides along: the rest's, or t when the parked
  ;; character was the last one.
  (let ((c (rontolisp::%unread-char-take stream)))
    (if c
        (if (char= c #\Newline)
            (%quiet-values "" nil)
            (let* ((vals
                    (%quiet-multiple-value-list (read-line stream nil nil)))
                   (rest (car vals)))
              (if rest
                  (%quiet-values (concatenate 'string (string c) rest)
                                 (car (cdr vals)))
                  (%quiet-values (string c) t))))
        (read-line stream eof-error-p eof-value))))

(defun rontolisp::%unread-listen (stream)
  ;; A parked character counts as one that remains; otherwise the stream itself.
  (if (rontolisp::%unread-parked (rontolisp::%unread-key stream))
      t
      (listen stream)))

(defun rontolisp::%unread-file-position (stream)
  (let* ((key (rontolisp::%unread-key stream))
         (position (file-position stream))
         (parked (rontolisp::%unread-parked key)))
    (if (if position parked nil)
        (let ((code (char-code parked)))
          (- position
             ;; A STRING stream counts characters, a file stream octets.
             (if (if (%obj-is key '%STREAM)
                     (equal (%obj-ref key 1) :string-input)
                     nil)
                 1
                 (if (< code 128)
                     1
                     (if (< code 2048) 2 (if (< code 65536) 3 4))))))
        position)))

(defun rontolisp::%unread-file-position-set (stream position)
  (rontolisp::%unread-char-take stream)
  (file-position stream position))
