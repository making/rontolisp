;; The trivial-gray-streams package: a thin adapter over rontolisp's own
;; Gray-stream extension (gray.lisp), satisfying the built-in ASDF system
;; "trivial-gray-streams" -- the same reasoning as the usocket shim: the real
;; library is a per-implementation portability layer that cannot know
;; rontolisp, so rontolisp carries the adaptation. A user class extends the
;; trivial-gray-streams base classes (or trivial-gray-stream-mixin plus one
;; of them) and defines methods on the trivial-gray-streams:stream-*
;; generics; the delegating methods below route rontolisp's protocol (which
;; the stream-taking built-ins dispatch to) into those generics. The
;; trivial-gray-streams classes mirror the rontolisp hierarchy INTERNALLY
;; (each subclasses its trivial-gray-streams parent as well as its rontolisp
;; twin) so one delegating method per generic, specialized on the
;; input/output root, covers every adapter subclass.
;; Written in canonical shape; the package is seeded in PackageRegistry.

(defclass trivial-gray-streams:fundamental-stream (rontolisp:fundamental-stream)
  ())

(defclass trivial-gray-streams:fundamental-input-stream
    (trivial-gray-streams:fundamental-stream rontolisp:fundamental-input-stream)
  ())

(defclass trivial-gray-streams:fundamental-output-stream (trivial-gray-streams:fundamental-stream
                                                          rontolisp:fundamental-output-stream)
  ())

(defclass trivial-gray-streams:fundamental-character-output-stream (trivial-gray-streams:fundamental-output-stream
                                                                    rontolisp:fundamental-character-output-stream)
  ())

(defclass trivial-gray-streams:fundamental-character-input-stream (trivial-gray-streams:fundamental-input-stream
                                                                   rontolisp:fundamental-character-input-stream)
  ())

(defclass trivial-gray-streams:fundamental-binary-input-stream (trivial-gray-streams:fundamental-input-stream
                                                                rontolisp:fundamental-binary-input-stream)
  ())

(defclass trivial-gray-streams:fundamental-binary-output-stream (trivial-gray-streams:fundamental-output-stream
                                                                 rontolisp:fundamental-binary-output-stream)
  ())

;; The portable mixin (upstream trivial-gray-streams' own class): carries no
;; behavior here because the sequence and file-position generics it exists
;; to portably dispatch are part of rontolisp's protocol already.

(defclass trivial-gray-streams:trivial-gray-stream-mixin () ())

;; The portable generics a user class defines methods on.

(defgeneric trivial-gray-streams:stream-write-char (stream character))

(defgeneric trivial-gray-streams:stream-write-string
    (stream string &optional start end))

(defgeneric trivial-gray-streams:stream-write-byte (stream byte))

(defgeneric trivial-gray-streams:stream-read-byte (stream))

(defgeneric trivial-gray-streams:stream-read-char (stream))

(defgeneric trivial-gray-streams:stream-unread-char (stream character))

(defgeneric trivial-gray-streams:stream-read-line (stream))

(defgeneric trivial-gray-streams:stream-listen (stream))

(defgeneric trivial-gray-streams:stream-read-sequence
    (stream sequence start end &key))

(defgeneric trivial-gray-streams:stream-write-sequence
    (stream sequence start end &key))

(defgeneric trivial-gray-streams:stream-file-position (stream))

(defgeneric (setf trivial-gray-streams:stream-file-position) (position stream))

;; Delegations: rontolisp's protocol routes into the portable generics.

(defmethod rontolisp:stream-write-char
    ((stream trivial-gray-streams:fundamental-output-stream) character)
  (trivial-gray-streams:stream-write-char stream character))

(defmethod rontolisp:stream-write-string ((stream
                                           trivial-gray-streams:fundamental-output-stream)
                                          string &optional start end)
  (trivial-gray-streams:stream-write-string stream string start end))

(defmethod rontolisp:stream-write-byte
    ((stream trivial-gray-streams:fundamental-output-stream) byte)
  (trivial-gray-streams:stream-write-byte stream byte))

(defmethod rontolisp:stream-read-byte
    ((stream trivial-gray-streams:fundamental-input-stream))
  (trivial-gray-streams:stream-read-byte stream))

(defmethod rontolisp:stream-read-char
    ((stream trivial-gray-streams:fundamental-input-stream))
  (trivial-gray-streams:stream-read-char stream))

(defmethod rontolisp:stream-unread-char
    ((stream trivial-gray-streams:fundamental-input-stream) character)
  (trivial-gray-streams:stream-unread-char stream character))

(defmethod rontolisp:stream-read-line
    ((stream trivial-gray-streams:fundamental-input-stream))
  (trivial-gray-streams:stream-read-line stream))

(defmethod rontolisp:stream-listen
    ((stream trivial-gray-streams:fundamental-input-stream))
  (trivial-gray-streams:stream-listen stream))

(defmethod rontolisp:stream-read-sequence
    ((stream trivial-gray-streams:fundamental-input-stream) sequence start end)
  (trivial-gray-streams:stream-read-sequence stream sequence start end))

(defmethod rontolisp:stream-write-sequence
    ((stream trivial-gray-streams:fundamental-output-stream) sequence start end)
  (trivial-gray-streams:stream-write-sequence stream sequence start end))

(defmethod rontolisp:stream-file-position
    ((stream trivial-gray-streams:fundamental-stream))
  (trivial-gray-streams:stream-file-position stream))

(defmethod (setf rontolisp:stream-file-position)
    (position (stream trivial-gray-streams:fundamental-stream))
  (setf (trivial-gray-streams:stream-file-position stream) position))

;; Portable-side defaults, reusing gray.lisp's element-at-a-time loops --
;; without these, the delegating methods above would shadow the rontolisp
;; base-class defaults for an adapter class that defines only the element
;; generics.

(defmethod trivial-gray-streams:stream-read-line
    ((stream trivial-gray-streams:fundamental-input-stream))
  (rontolisp::%gray-default-read-line stream))

(defmethod trivial-gray-streams:stream-listen
    ((stream trivial-gray-streams:fundamental-input-stream))
  nil)

(defmethod trivial-gray-streams:stream-read-sequence ((stream
                                                       trivial-gray-streams:fundamental-input-stream)
                                                      sequence start end &key)
  (rontolisp::%gray-default-read-sequence stream sequence start end))

(defmethod trivial-gray-streams:stream-write-sequence ((stream
                                                        trivial-gray-streams:fundamental-output-stream)
                                                       sequence start end &key)
  (rontolisp::%gray-default-write-sequence stream sequence start end))

(defmethod trivial-gray-streams:stream-file-position
    ((stream trivial-gray-streams:fundamental-stream))
  nil)

(defmethod (setf trivial-gray-streams:stream-file-position)
    (position (stream trivial-gray-streams:fundamental-stream))
  nil)
