;;;; Hand-authored replacement for cl-postgres.asd (Postmodern's low-level
;;;; driver) that DECLARES the dependencies the sources actually reference.
;;;;
;;;; The upstream .asd under-declares: sql-string.lisp calls
;;;; alexandria:proper-list-p and data-types.lisp calls cl-ppcre, but neither
;;;; system appears in :depends-on (on a full CL image both are practically
;;;; always loaded by something else, so upstream never notices). The
;;;; interpreter tolerates the gap through late binding, but the compile
;;;; path's package resolver reads every inlined form eagerly and fails with
;;;; "No such package: ALEXANDRIA". The usocket dependency also loses its
;;;; reader-conditional wrapper here: rontolisp's feature set always takes the
;;;; usocket branch of communicate.lisp.
;;;;
;;;; Component paths resolve against the directory of the located
;;;; cl-postgres.asd, so the REAL library sources are loaded; only the system
;;;; metadata is redeclared. The #.*string-file* component of the original is
;;;; pinned to "strings-utf-8", the branch upstream's own #+(or sb-unicode
;;;; unicode ...) test selects here: rontolisp advertises :unicode because its
;;;; characters are code points on every backend. The other branch,
;;;; "strings-ascii", announces client_encoding SQL_ASCII in the startup packet
;;;; and then writes one octet per code point, so any non-ASCII parameter is
;;;; truncated into a byte sequence the server rejects.

(defsystem "cl-postgres"
  :description "Low-level client library for PostgreSQL"
  :depends-on ("md5" "split-sequence" "ironclad" "cl-base64" "uax-15"
               "cl-ppcre" "alexandria" "usocket")
  :components
  ((:module "cl-postgres"
    :serial t
    :components ((:file "package")
                 (:file "features")
                 (:file "config")
                 (:file "oid")
                 (:file "errors")
                 (:file "data-types")
                 (:file "sql-string")
                 (:file "trivial-utf-8")
                 (:file "strings-utf-8")
                 (:file "communicate")
                 (:file "messages")
                 (:file "ieee-floats")
                 (:file "interpret")
                 (:file "saslprep")
                 (:file "scram")
                 (:file "protocol")
                 (:file "public")
                 (:file "bulk-copy")))))
