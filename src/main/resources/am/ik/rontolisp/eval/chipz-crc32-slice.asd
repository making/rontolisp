;;;; Hand-authored replacement for chipz.asd -- a CRC32 slice, not the decompressor.
;;;;
;;;; The only consumer in the whole dependency closure is mito-migration's
;;;; src/migration/util.lisp, which imports EXACTLY make-crc32 / update-crc32 /
;;;; produce-crc32 to derive a PostgreSQL advisory-lock id from the migration
;;;; database name. Nothing anywhere calls chipz:decompress. Loading the real
;;;; chipz.asd would drag in inflate + bzip2 + gzip/zlib containers -- roughly
;;;; 2,600 lines of decompressor -- for three functions in one 22-line file.
;;;;
;;;; crc32.lisp is self-contained: its #-sbcl branch (the one rontolisp takes)
;;;; defines the crc32 struct, builds *crc32-table* at load time and computes
;;;; over a plain octet vector. It names chipz's own INDEX and
;;;; SIMPLE-OCTET-VECTOR deftypes -- from types-and-tables.lisp -- but ONLY
;;;; inside `declare` forms, which are no-ops here (.kb/declarations-type-checks.md),
;;;; so types-and-tables.lisp and the constants.lisp it reads +max-code-length+
;;;; from are both out. Verified: chipz's crc32 of "mydb" is 285543882 on this
;;;; slice and on SBCL 2.2.9 loading the real full chipz.
;;;;
;;;; Component paths resolve against the located chipz.asd's directory, so the
;;;; REAL library sources are loaded; only the component list is redeclared.
;;;;
;;;; RE-EVALUATION TRIGGER: the moment any system in a supported closure calls
;;;; chipz:decompress / make-dstate / make-decompressing-stream (a gzip- or
;;;; zlib-reading client), this slice is no longer enough -- widen it to the
;;;; real component list and expect the inflate/bzip2 bodies to need work
;;;; (types-and-tables.lisp:107 alone needs `fill`, which rontolisp lacks).
;;;;
;;;; The real chipz.asd is parseable as data since todo-241 (its top-level
;;;; defclass/defpackage forms are skipped), so this override is a SCOPE
;;;; decision, not a parse workaround: dropping it loads a much larger system
;;;; that currently fails.

(defsystem "chipz"
  :version "0.8"
  :serial t
  :components ((:file "package") (:file "crc32")))
