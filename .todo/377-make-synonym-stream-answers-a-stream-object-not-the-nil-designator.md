# `make-synonym-stream` answers a stream object, not the nil designator

Difficulty: Medium

Part of `.todo/372` (rove). The re-evaluation trigger written in
`.kb/read-load-streams.md` ("if a consumer ever rebinds the symbol behind a
NON-standard synonym stream, the runtime needs that designator kind") has a
neighbour that fired first: a consumer that TESTS the value.

Today `(make-synonym-stream '*standard-output*)` returns NIL -- the designator
every writer resolves through the current `*standard-output*` -- and any other
symbol is read ONCE at construction. Rove:

```lisp
(defvar *report-stream* (make-synonym-stream '*standard-output*))
(defclass reporter (stats) ((stream :initarg :stream :accessor reporter-stream)))
(defun make-reporter (style &key (stream *report-stream*)) ... (make-instance class :stream stream))
(defmethod initialize-instance :after ((reporter spec-reporter) &rest initargs &key stream &allow-other-keys)
  (when stream
    (setf (reporter-stream reporter) (make-indent-stream stream))))
```

`stream` is NIL, the indent stream is never wrapped, `(reporter-stream r)` stays
NIL, and the first `(incf (stream-indent-level stream) 2)` dies with "No
applicable method: STREAM-INDENT-LEVEL on NULL". The roswell `rove` script
rebinds `*standard-output*` to a broadcast stream while the report keeps
flowing through the synonym to the real one -- the per-operation forwarding IS
the feature, and it must be a value.

## Shape

A synonym stream is a distinct VALUE, the pathname precedent
(`.kb/pathnames.md`): a fixed `LispLayout.SYNONYM_STREAM` instance holding the
symbol; `make-synonym-stream` builds it, `synonym-stream-symbol` reads it,
`streamp`/`input-stream-p`/`output-stream-p` are true, `close` is a no-op t. Every
stream-designator resolution -- interpreter `emitTo`/the read helpers, JVM
`_writeStr` & co, WASM's write/read helpers, and the `%gray-*-dispatch` defuns
(they see the instance FIRST, before the Gray generic) -- resolves the symbol's
CURRENT value (`symbol-value`, dynamic-binding-aware on the interpreter, the
special-variable global on the compilers) and recurses, so ANY symbol forwards
per operation and the "resolved once" divergence goes with the nil trick.
`*standard-output*`/`*standard-input*` need no special case any more.

Acceptance: `(when (make-synonym-stream '*standard-output*) ...)` true; writing
through a synonym over a user special that is rebound afterwards follows the
new binding (the shape the kb names as the trigger); a Gray instance wrapping a
synonym stream (rove's exact composition) writes through both; the existing
`makeSynonymStreamResolvesTheNamedVariable` /
`synonymStreamOverStandardOutputFollowsALaterBinding` pins move to the object;
`ci-spec.yaml` case; `.kb/read-load-streams.md` paragraph + the
`make-synonym-stream` doc page lose the "lite" clause.
