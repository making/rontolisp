# fast-io / circular-streams: the two remaining ql:quickload blockers

Difficulty: 中 (two independent, precisely-located mechanisms)

Follow-up of `.todo/235` (Gray binary/input protocol + stream-file-position,
shipped 2026-08-02, `.kb/gray-streams.md`). The re-probe result: **both
libraries' SOURCE FILES load and run cleanly** when loaded by hand
(`(load ".../src/package.lisp")` etc. after `ql:quickload` of alexandria +
trivial-gray-streams) — the Gray substrate no longer blocks anything. What
still fails is `(ql:quickload "fast-io")` itself, on two mechanisms this todo
tracks:

## 1. `.asd` parser: `eval-when` + `pushnew :feature *features*`

`AsdfSystems.parseAsdSource` hard-errors on any form outside
DEFSYSTEM/DEFPACKAGE/IN-PACKAGE/DEFPARAMETER/REGISTER-SYSTEM-PACKAGES.
fast-io.asd opens with:

```lisp
(eval-when (:compile-toplevel :load-toplevel :execute)
  (pushnew :fast-io *features*))
#+(or sbcl ccl cmucl ecl lispworks allegro)
(eval-when (:compile-toplevel :load-toplevel :execute)
  (pushnew :fast-io-sv *features*))
```

- The second one is `#+`-gated OFF on rontolisp (so `:fast-io-sv` never
  activates, the `:depends-on (#+fast-io-sv :static-vectors)` entry drops out,
  and io.lisp's `#-fast-io-sv` branches are the ones read — all correct with
  NO feature support beyond skipping the form).
- Minimal fix shape: accept a top-level `(eval-when (...) body...)` whose body
  forms are each `(pushnew :KEYWORD *features*)`, adding the keyword to the
  feature set used for the REST of the parse (the `#+fast-io-sv` inside the
  defsystem) and for reading the system's components. Grep `.kb/asdf.md` and
  `.kb/reader-features.md` first; check where the parse-time `Features` would
  have to flow. `.todo/181` is the GENERAL "a program cannot observe its own
  `*features*` pushes" gap (reader + compile path); this item is only the .asd
  PARSER rejecting the form — fixing it here must not fork from whatever 181
  decides for source files.

## 2. `with-slots` over an UNBOUND slot (write-only use)

`LispMacroExpander.expandWithSlots` substitutes textual slot references but
ALSO binds every named slot to an entry-time `(slot-value obj 'slot)` read as
a fallback for runtime-generated code. That eager read SIGNALS "The slot X is
unbound" for a slot the body only WRITES — fast-io's exact idiom:

```lisp
(defmethod initialize-instance ((self fast-output-stream) &key stream ...)
  (call-next-method)
  (with-slots (buffer) self          ; buffer has no :initform -> unbound
    (setf buffer (make-output-buffer :output stream))))
```

so `(make-instance 'fast-io:fast-input-stream ...)` dies. (Via the full
fast-io load the symptom surfaced as "No applicable method: CLOSE on INTEGER"
— same site, different route; the minimal repro in the deleted scratch test
gives the slot-unbound message directly.) Fix direction: make the fallback
binding unbound-tolerant — e.g. bind through a
`(if (slot-boundp obj 'slot) (slot-value obj 'slot) nil)` read — WITHOUT
changing the textual-substitution semantics; check `slot-boundp` compiles on
all four backends before choosing that spelling (`.kb/clos.md`).

## Re-probe (acceptance)

`(ql:quickload "fast-io")` then `(ql:quickload "circular-streams")`, then the
functional loop: `make-circular-input-stream` over a
`fast-io:fast-input-stream`, `read-byte` past EOF wrapping the position back
to 0, `(setf stream-file-position)` via `file-position`. All four backends for
whatever ships; `.todo/231` tracks the wider lack milestone.
