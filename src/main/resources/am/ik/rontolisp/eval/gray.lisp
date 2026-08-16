;; rontolisp's own Gray-stream extension: the base classes and generic
;; functions a user-defined stream implements, mirroring how real
;; implementations expose their native Gray support (sb-gray, ccl's
;; ansi-streams, ...). The stream-taking built-ins (write-string/write-char,
;; princ/prin1/print, terpri/fresh-line/write-line,
;; force-output/finish-output/clear-output, close, write-byte,
;; read-byte/read-char/read-char-no-hang/peek-char/unread-char/read-line,
;; listen, read-sequence/write-sequence, file-position, and the stream
;; predicates open-stream-p/input-stream-p/output-stream-p plus
;; stream-element-type) dispatch to the rontolisp:stream-* generics when handed
;; a CLOS instance instead of a stream handle. Third-party portability layers
;; adapt to THIS protocol (see trivial-gray-streams.lisp); no third-party name
;; is known to the core.
;;
;; Write-side requirement: a character output stream defines stream-write-char
;; OR stream-write-string -- each has a default method written in terms of the
;; other, so exactly one is enough and the rest of the output protocol
;; (terpri, fresh-line, write-line, the print family) composes out of them.
;; Defining NEITHER is the one broken shape: the two defaults then call each
;; other.
;;
;; Read-side requirement: a character input stream defines stream-read-char and
;; nothing else is mandatory (a binary one defines stream-read-byte). Every
;; other read generic has a default written over it -- stream-read-line and
;; stream-read-sequence loop it, stream-read-char-no-hang IS it, and
;; stream-peek-char reads one and pushes it back through stream-unread-char,
;; whose own default parks the character in the protocol's one-slot pushback
;; cell. A class that can rewind its source defines stream-unread-char and owns
;; the pushback instead; the cell is then never written.
;;
;; Read-side EOF convention: stream-read-byte / stream-read-char /
;; stream-read-line / stream-peek-char / stream-read-char-no-hang return the
;; keyword :eof at end of stream; the built-in dispatch translates that into
;; the eof-error-p / eof-value contract (signalling end-of-file like the
;; handle-based built-ins). stream-read-line answers a partial last line as
;; that line; :eof means "no characters left at all". Primary values only -- no
;; (values line missing-newline-p) pair crosses a function boundary on the
;; compile backends.

(defclass rontolisp:fundamental-stream () ())

(defclass rontolisp:fundamental-input-stream (rontolisp:fundamental-stream) ())

(defclass rontolisp:fundamental-output-stream (rontolisp:fundamental-stream) ())

(defclass rontolisp:fundamental-character-output-stream
    (rontolisp:fundamental-output-stream)
  ())

(defclass rontolisp:fundamental-character-input-stream
    (rontolisp:fundamental-input-stream)
  ())

(defclass rontolisp:fundamental-binary-input-stream
    (rontolisp:fundamental-input-stream)
  ())

(defclass rontolisp:fundamental-binary-output-stream
    (rontolisp:fundamental-output-stream)
  ())

;; Output generics.

(defgeneric rontolisp:stream-write-char (stream character))

(defgeneric rontolisp:stream-write-string (stream string &optional start end))

(defgeneric rontolisp:stream-write-byte (stream byte))

;; Column / line-position protocol and the line-oriented output generics CL's
;; Gray protocol names, so terpri / fresh-line / write-line / the print family
;; reach a user stream instead of writing past it.

(defgeneric rontolisp:stream-line-column (stream))

(defgeneric rontolisp:stream-start-line-p (stream))

(defgeneric rontolisp:stream-terpri (stream))

(defgeneric rontolisp:stream-fresh-line (stream))

(defgeneric rontolisp:stream-advance-to-column (stream column))

;; Flush / discard / close. Nothing is buffered in a discardable way on any
;; backend, so the defaults are no-ops; a stream wrapping something that IS
;; buffered overrides them.

(defgeneric rontolisp:stream-force-output (stream))

(defgeneric rontolisp:stream-finish-output (stream))

(defgeneric rontolisp:stream-clear-output (stream))

;; Input generics.

(defgeneric rontolisp:stream-read-byte (stream))

(defgeneric rontolisp:stream-read-char (stream))

(defgeneric rontolisp:stream-read-char-no-hang (stream))

(defgeneric rontolisp:stream-peek-char (stream))

(defgeneric rontolisp:stream-unread-char (stream character))

(defgeneric rontolisp:stream-read-line (stream))

(defgeneric rontolisp:stream-listen (stream))

;; Sequence generics: start/end are always integers by the time a method
;; runs -- the dispatch helpers below normalize a missing end to
;; (length sequence), mirroring the built-in read-sequence/write-sequence.

(defgeneric rontolisp:stream-read-sequence (stream sequence start end))

(defgeneric rontolisp:stream-write-sequence (stream sequence start end))

;; file-position protocol: (file-position s) reads through the generic,
;; (file-position s pos) writes through the (setf ...) generic.

(defgeneric rontolisp:stream-file-position (stream))

(defgeneric (setf rontolisp:stream-file-position) (position stream))

;; Default methods: element-at-a-time fallbacks mirroring full Gray's
;; defaults, so a class defining only the element generics still answers
;; read-line and the sequence built-ins. The loops are plain defuns so the
;; trivial-gray-streams shim can reuse them for its own defaults.

;; The protocol's ONE-SLOT pushback: stream-unread-char's default method parks
;; the character here and %gray-read-char-1 -- the single read-one-character
;; entry every default and every read dispatch helper goes through -- drains it
;; first. One character for one stream at a time, which is what CL promises for
;; unread-char and exactly the shape the WASM backend's own fd pushback has.
;; A class that defines stream-unread-char itself never reaches this cell: its
;; method rewinds its own source and its stream-read-char answers the rewound
;; character.

(defvar rontolisp::*gray-unread-stream* nil)

(defvar rontolisp::*gray-unread-char* nil)

(defun rontolisp::%gray-default-unread-char (stream character)
  (setq rontolisp::*gray-unread-stream* stream)
  (setq rontolisp::*gray-unread-char* character)
  nil)

(defun rontolisp::%gray-read-char-1 (stream)
  (if (eq rontolisp::*gray-unread-stream* stream)
      (let ((c rontolisp::*gray-unread-char*))
        (setq rontolisp::*gray-unread-stream* nil)
        (setq rontolisp::*gray-unread-char* nil)
        c)
      (rontolisp:stream-read-char stream)))

;; The five characters CL's standard readtable calls whitespace -- the set
;; peek-char's t peek-type skips. Kept in step with Environment's
;; isLispWhitespace and LispMacroExpander.whitespaceCharTest, the other two
;; copies of this set.
(defun rontolisp::%gray-whitespace-char-p (c)
  (or (char= c #\Space) (char= c #\Tab) (char= c #\Newline) (char= c #\Return)
      (char= c #\Page)))

(defun rontolisp::%gray-default-peek-char (stream)
  (let ((c (rontolisp::%gray-read-char-1 stream)))
    (if (eq c :eof)
        :eof (progn
               (rontolisp:stream-unread-char stream c)
               c))))

(defun rontolisp::%gray-default-read-line (stream)
  (let ((acc "") (result nil) (done nil))
    (do ()
        (done result)
      (let ((c (rontolisp::%gray-read-char-1 stream)))
        (cond ((eq c :eof)
               (setq result (if (string= acc "") :eof acc))
               (setq done t))
              ((char= c #\Newline)
               (setq result acc)
               (setq done t))
              (t (setq acc (concatenate 'string acc (string c)))))))))

(defun rontolisp::%gray-default-read-sequence (stream sequence start end)
  (let ((i start) (done nil) (chars (stringp sequence)))
    (do ()
        ((or done (>= i end)) i)
      (let ((elt
             (if chars
                 (rontolisp::%gray-read-char-1 stream)
                 (rontolisp:stream-read-byte stream))))
        (if (eq elt :eof)
            (setq done t)
            (progn
              (setf (aref sequence i) elt)
              (setq i (+ i 1))))))))

(defun rontolisp::%gray-default-write-sequence (stream sequence start end)
  (let ((i start) (chars (stringp sequence)))
    (do ()
        ((>= i end) sequence)
      (if chars
          (rontolisp:stream-write-char stream (aref sequence i))
          (rontolisp:stream-write-byte stream (aref sequence i)))
      (setq i (+ i 1)))))

(defun rontolisp::%gray-default-write-string (stream string start end)
  (let ((i (if start start 0)) (n (if end end (length string))))
    (do ()
        ((>= i n) string)
      (rontolisp:stream-write-char stream (aref string i))
      (setq i (+ i 1)))))

(defun rontolisp::%gray-default-write-char (stream character)
  (rontolisp:stream-write-string stream (string character))
  character)

(defun rontolisp::%gray-default-terpri (stream)
  (rontolisp:stream-write-char stream #\Newline)
  nil)

(defun rontolisp::%gray-default-start-line-p (stream)
  (let ((column (rontolisp:stream-line-column stream)))
    (if column (eql column 0) nil)))

;; fresh-line's answer is CL's: t when it had to break the line, nil when the
;; stream was already at one. A stream with no column cannot tell, so
;; %gray-default-start-line-p answers nil for it and the line break is
;; unconditional -- the same rule the handle-based built-in follows for a file
;; stream (.kb/pretty-printer.md).
(defun rontolisp::%gray-default-fresh-line (stream)
  (if (rontolisp:stream-start-line-p stream)
      nil
      (progn
        (rontolisp:stream-terpri stream)
        t)))

(defun rontolisp::%gray-default-advance-to-column (stream column)
  (let ((at (rontolisp:stream-line-column stream)))
    (if at
        (progn
          (do ()
              ((>= at column) t)
            (rontolisp:stream-write-char stream #\Space)
            (setq at (+ at 1)))
          t)
        nil)))

;; The write-side pair: each element generic has a default written in terms of
;; the other, so a class defines whichever one it can and inherits the rest of
;; the output protocol. stream-write-char is the one full Gray requires; the
;; write-string default is the loop every implementation ships, and the
;; write-char default is what keeps a stream that only knows how to write
;; STRINGS (the shape rontolisp's own broadcast stream and jzon's writer use)
;; answering write-char.

(defmethod rontolisp:stream-write-string ((stream
                                           rontolisp:fundamental-character-output-stream)
                                          string &optional start end)
  (rontolisp::%gray-default-write-string stream string start end))

(defmethod rontolisp:stream-write-char
    ((stream rontolisp:fundamental-character-output-stream) character)
  (rontolisp::%gray-default-write-char stream character))

;; Column defaults: nil is CL's "this stream does not track a column".

(defmethod rontolisp:stream-line-column
    ((stream rontolisp:fundamental-character-output-stream))
  nil)

(defmethod rontolisp:stream-start-line-p
    ((stream rontolisp:fundamental-character-output-stream))
  (rontolisp::%gray-default-start-line-p stream))

(defmethod rontolisp:stream-terpri
    ((stream rontolisp:fundamental-character-output-stream))
  (rontolisp::%gray-default-terpri stream))

(defmethod rontolisp:stream-fresh-line
    ((stream rontolisp:fundamental-character-output-stream))
  (rontolisp::%gray-default-fresh-line stream))

(defmethod rontolisp:stream-advance-to-column
    ((stream rontolisp:fundamental-character-output-stream) column)
  (rontolisp::%gray-default-advance-to-column stream column))

;; Flush / discard / close defaults.

(defmethod rontolisp:stream-force-output
    ((stream rontolisp:fundamental-output-stream))
  nil)

(defmethod rontolisp:stream-finish-output
    ((stream rontolisp:fundamental-output-stream))
  nil)

(defmethod rontolisp:stream-clear-output
    ((stream rontolisp:fundamental-output-stream))
  nil)

(defmethod rontolisp:stream-read-line
    ((stream rontolisp:fundamental-input-stream))
  (rontolisp::%gray-default-read-line stream))

;; The read-side pair to the write side's write-char/write-string defaults:
;; stream-read-char is the ONE method a character input stream must supply and
;; everything else is written over it. stream-read-char-no-hang IS
;; stream-read-char (rontolisp has no non-blocking source a class could not
;; wrap itself); stream-peek-char reads one and hands it back through
;; stream-unread-char, whose default parks it in the protocol's pushback cell.

(defmethod rontolisp:stream-read-char-no-hang
    ((stream rontolisp:fundamental-input-stream))
  (rontolisp::%gray-read-char-1 stream))

(defmethod rontolisp:stream-peek-char
    ((stream rontolisp:fundamental-input-stream))
  (rontolisp::%gray-default-peek-char stream))

(defmethod rontolisp:stream-unread-char
    ((stream rontolisp:fundamental-input-stream) character)
  (rontolisp::%gray-default-unread-char stream character))

(defmethod rontolisp:stream-listen ((stream rontolisp:fundamental-input-stream))
  nil)

(defmethod rontolisp:stream-read-sequence
    ((stream rontolisp:fundamental-input-stream) sequence start end)
  (rontolisp::%gray-default-read-sequence stream sequence start end))

(defmethod rontolisp:stream-write-sequence
    ((stream rontolisp:fundamental-output-stream) sequence start end)
  (rontolisp::%gray-default-write-sequence stream sequence start end))

;; file-position defaults: nil is CL's "not supported" answer, the same one
;; the handle-based built-in gives.

(defmethod rontolisp:stream-file-position
    ((stream rontolisp:fundamental-stream))
  nil)

(defmethod (setf rontolisp:stream-file-position)
    (position (stream rontolisp:fundamental-stream))
  nil)

;; The built-in dispatch helpers the compile path rewrites call sites onto
;; (GrayStreamsLibrary.process) and the interpreter's built-in wraps delegate
;; to: a CLOS instance stream goes to the Gray generic, anything else falls
;; back to the built-in. The interpreter needs no rewrite -- its built-in
;; wraps call these helpers directly.
;;
;; Every helper resolves its stream through %synonym-target FIRST: a synonym
;; stream is an instance too, so without that it would take the CLOS arm and
;; die on "no applicable method", and its target -- which may itself be a Gray
;; instance -- would never be reached. %gray-close-dispatch is the one
;; exception; see its comment.

(defun rontolisp::%gray-write-string-dispatch (s stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (rontolisp:stream-write-string stream s)
        (write-string s stream))))

(defun rontolisp::%gray-write-char-dispatch (c stream)
  ;; write-char reaches stream-write-char -- the one method full Gray requires
  ;; and, for a class that defines only it, the ONLY writer it has. The
  ;; non-instance fallback is what write-char lowers to everywhere else.
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-write-char stream c)
          c)
        (progn
          (write-string (string c) stream)
          c))))

;; The line-oriented and print-family helpers. princ/prin1/print RENDER through
;; the ordinary value printer and hand the text to stream-write-string, so a
;; print-object method still decides the text (princ-to-string / prin1-to-string
;; are what the print-object rewrite hooks) and the Gray stream sees exactly the
;; bytes the handle-based built-in would have written -- print's newline
;; included, trailing where rontolisp's print puts it.

(defun rontolisp::%gray-princ-dispatch (value stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-write-string stream (princ-to-string value))
          value)
        (princ value stream))))

(defun rontolisp::%gray-prin1-dispatch (value stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-write-string stream (prin1-to-string value))
          value)
        (prin1 value stream))))

(defun rontolisp::%gray-print-dispatch (value stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-write-string stream (prin1-to-string value))
          (rontolisp:stream-terpri stream)
          value)
        (print value stream))))

(defun rontolisp::%gray-terpri-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-terpri stream)
          nil)
        (terpri stream))))

(defun rontolisp::%gray-fresh-line-dispatch (stream)
  ;; nil, like the handle-based fresh-line: the value of the operator does not
  ;; depend on which kind of stream it was handed. stream-fresh-line's own
  ;; CL-shaped t/nil answer is still what a direct caller (and the shim's
  ;; delegation) sees.
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-fresh-line stream)
          nil)
        (fresh-line stream))))

(defun rontolisp::%gray-write-line-dispatch (s stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-write-string stream s)
          (rontolisp:stream-terpri stream)
          s)
        (write-line s stream))))

(defun rontolisp::%gray-force-output-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-force-output stream)
          nil)
        (force-output stream))))

(defun rontolisp::%gray-finish-output-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-finish-output stream)
          nil)
        (finish-output stream))))

(defun rontolisp::%gray-clear-output-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-clear-output stream)
          nil)
        (clear-output stream))))

;; close: a Gray stream has nothing to release, so closing one answers t, CL's
;; own close value. A stream that DOES hold something defines
;; (defmethod close ((s my-stream) &key abort) ...) -- CL's spelling for exactly
;; this, dispatched on every backend by the shadowed-built-in machinery
;; (.kb/clos.md) -- and there is deliberately no second, rontolisp-only generic
;; competing with it: this helper is spliced, and the call sites rewritten, only
;; for a program that defines no close method of its own.
;; The ONE helper that does NOT resolve a synonym stream first: closing a
;; synonym closes the SYNONYM, not the stream it forwards to (CLHS 21.1.3), and
;; a synonym is an instance, so the instance arm is already its answer.
(defun rontolisp::%gray-close-dispatch (stream)
  (if (%obj-p stream) t (close stream)))

(defun rontolisp::%gray-write-byte-dispatch (byte stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-write-byte stream byte)
          byte)
        (write-byte byte stream))))

(defun rontolisp::%gray-read-byte-dispatch (stream eof-error-p eof-value)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (let ((b (rontolisp:stream-read-byte stream)))
          (if (eq b :eof) (if eof-error-p (error 'end-of-file) eof-value) b))
        (read-byte stream eof-error-p eof-value))))

(defun rontolisp::%gray-read-char-dispatch (stream eof-error-p eof-value)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (let ((c (rontolisp::%gray-read-char-1 stream)))
          (if (eq c :eof) (if eof-error-p (error 'end-of-file) eof-value) c))
        (read-char stream eof-error-p eof-value))))

(defun rontolisp::%gray-read-char-no-hang-dispatch
    (stream eof-error-p eof-value)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (let ((c (rontolisp:stream-read-char-no-hang stream)))
          (if (eq c :eof) (if eof-error-p (error 'end-of-file) eof-value) c))
        (read-char-no-hang stream eof-error-p eof-value))))

;; peek-char carries CL's peek-type argument, and the SKIPPING forms have to be
;; looped here rather than left to LispMacroExpander.expandPeekChar: that
;; expansion runs after this rewrite, so its %peek-char / read-char calls would
;; never see the instance. nil peeks, t skips whitespace, a character skips up
;; to that character, and in every case the character stopped on stays in the
;; stream -- the same contract the handle-based built-in follows (CL 21.2).
(defun rontolisp::%gray-peek-char-dispatch
    (peek-type stream eof-error-p eof-value)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (let ((result nil) (done nil))
          (do ()
              (done result)
            (let ((c (rontolisp:stream-peek-char stream)))
              (cond ((eq c :eof)
                     (if eof-error-p (error 'end-of-file) nil)
                     (setq result eof-value)
                     (setq done t))
                    ((null peek-type)
                     (setq result c)
                     (setq done t))
                    ((eq peek-type t)
                     (if (rontolisp::%gray-whitespace-char-p c)
                         (rontolisp::%gray-read-char-1 stream)
                         (progn
                           (setq result c)
                           (setq done t))))
                    (t (if (char= c peek-type)
                           (progn
                             (setq result c)
                             (setq done t))
                           (rontolisp::%gray-read-char-1 stream)))))))
        (peek-char peek-type stream eof-error-p eof-value))))

(defun rontolisp::%gray-unread-char-dispatch (character stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-unread-char stream character)
          nil)
        (unread-char character stream))))

;; open-stream-p / stream-element-type follow the close rule: CL spells both as
;; ordinary functions a program may own with a defmethod, so there is no
;; competing rontolisp: generic and these helpers are used only when the
;; program defines no method of its own. An instance with no method is OPEN (a
;; Gray stream holds nothing that could be shut) and answers the element type
;; of the base class it extends.

(defun rontolisp::%gray-open-stream-p-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream) t (open-stream-p stream))))

(defun rontolisp::%gray-stream-element-type-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (if (or (typep stream 'rontolisp:fundamental-binary-input-stream)
                (typep stream 'rontolisp:fundamental-binary-output-stream))
            '(unsigned-byte 8)
            'character)
        (stream-element-type stream))))

(defun rontolisp::%gray-read-line-dispatch (stream eof-error-p eof-value)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (let ((l (rontolisp:stream-read-line stream)))
          (if (eq l :eof) (if eof-error-p (error 'end-of-file) eof-value) l))
        (read-line stream eof-error-p eof-value))))

(defun rontolisp::%gray-listen-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (if (rontolisp:stream-listen stream) t nil)
        (listen stream))))

(defun rontolisp::%gray-read-sequence-dispatch (sequence stream start end)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (rontolisp:stream-read-sequence stream sequence start
                                        (if end end (length sequence)))
        (read-sequence sequence stream
                       :start start
                       :end (if end end (length sequence))))))

(defun rontolisp::%gray-write-sequence-dispatch (sequence stream start end)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (progn
          (rontolisp:stream-write-sequence stream sequence start
                                           (if end end (length sequence)))
          sequence)
        (write-sequence sequence stream
                        :start start
                        :end (if end end (length sequence))))))

(defun rontolisp::%gray-file-position-dispatch (stream)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (rontolisp:stream-file-position stream)
        (file-position stream))))

(defun rontolisp::%gray-file-position-set-dispatch (stream position)
  (let ((stream (%synonym-target stream)))
    (if (%obj-p stream)
        (setf (rontolisp:stream-file-position stream) position)
        (file-position stream position))))
