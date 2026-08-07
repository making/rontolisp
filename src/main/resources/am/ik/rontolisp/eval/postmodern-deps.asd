;;;; Hand-authored replacement for postmodern.asd (the PostgreSQL programming
;;;; API on top of cl-postgres) that MAKES THE TWO FEATURE DECISIONS STATICALLY
;;;; and declares the dependencies the sources actually reference.
;;;;
;;;; The upstream .asd opens with a top-level eval-when pushing
;;;; :postmodern-thread-safe / :postmodern-use-mop onto *features* per
;;;; implementation. AsdfSystems never evaluates a system definition (only
;;;; defsystem/defpackage/in-package/pure-data defparameter are recognized), so
;;;; that form makes the file unreadable -- and a push would be invisible to the
;;;; reader anyway (.todo/181). Both decisions are therefore taken here, once:
;;;;
;;;; :postmodern-use-mop -- ON. table.lisp (the :if-feature component below)
;;;; joins the build and postmodern's own defpackage takes its
;;;; #+postmodern-use-mop branch, using :closer-common-lisp. That is the full
;;;; MOP build: the DAO layer runs on the static definition-time MOP subset
;;;; (defclass :metaclass protocol + build-dao-methods' compile interception --
;;;; .kb/clos.md). The closer-mop dependency is carried in upstream's own
;;;; (:feature ...) shape so the whole decision stays a feature flip.
;;;;
;;;; :postmodern-thread-safe -- ON (.todo/204). rontolisp DOES run concurrent
;;;; handlers (one virtual thread per request under `serve`), so postmodern's
;;;; three locks guard state that can really be raced here: the connection pool
;;;; (connect.lisp), the prepared-statement id counter (prepare.lisp) and class
;;;; finalization (query.lisp), plus five more in the MOP-only table.lisp. The
;;;; feature is declared through :rontolisp-features below, which is what makes
;;;; it visible to the READER for this system's own component files -- a
;;;; *features* push from an eval-when never is (.todo/181). It is honest to
;;;; declare because bordeaux-threads' `with-lock-held` now really serializes:
;;;; the bt shim is a built-in system over the rontolisp:*-mutex primitives,
;;;; which are a ReentrantLock on the interpreter and the JVM and no-ops on the
;;;; two WASM backends (single-threaded by construction, so exclusion there is
;;;; a tautology, not a lie). `java:` monitors were never an option -- the
;;;; native binary has no reflection metadata.
;;;;
;;;; :depends-on -- "global-vars" is dropped: upstream declares it but has ZERO
;;;; call sites (it is a bordeaux-threads dependency that leaked into this
;;;; list), and its non-SBCL branch needs define-symbol-macro and remprop,
;;;; neither of which exists here. "bordeaux-threads" is declared, matching the
;;;; feature decision above. "cl-ppcre" (roles.lisp, execute-file.lisp) and
;;;; "uax-15" (util.lisp) are undeclared upstream and added here: they load
;;;; transitively through cl-postgres today, so leaving them out would make the
;;;; eagerly resolving compile paths depend on the order of somebody else's
;;;; .asd.
;;;;
;;;; Component paths resolve against the directory of the located postmodern.asd,
;;;; so the REAL library sources are loaded; only the system metadata is
;;;; redeclared. The postmodern/tests system is not reproduced -- it needs
;;;; fiveam, simple-date and local-time, none of which load here.

(defsystem "postmodern"
  :description "PostgreSQL programming API"
  :rontolisp-features (:postmodern-thread-safe :postmodern-use-mop)
  :depends-on ("alexandria" "bordeaux-threads" "cl-postgres" "s-sql"
               "split-sequence" "uiop" "cl-ppcre" "uax-15"
               (:feature :postmodern-use-mop "closer-mop"))
  :components ((:module "postmodern"
                        :components ((:file "package") (:file "config")
                                     (:file "connect"
                                            :depends-on ("package" "config"))
                                     (:file "json-encoder"
                                            :depends-on ("package" "config"))
                                     (:file "query"
                                            :depends-on
                                            ("connect" "json-encoder" "config"))
                                     (:file "prepare"
                                            :depends-on ("query" "config"))
                                     (:file "roles"
                                            :depends-on ("query" "config"))
                                     (:file "util"
                                      :depends-on ("query" "roles" "config"))
                                     (:file "transaction"
                                            :depends-on ("query" "config"))
                                     (:file "namespace"
                                            :depends-on ("query" "config"))
                                     (:file "execute-file"
                                            :depends-on ("query" "config"))
                                     (:file "table"
                                            :depends-on
                                            ("util" "transaction" "query"
                                             "config")
                                            :if-feature :postmodern-use-mop)
                                     (:file "deftable"
                                            :depends-on
                                            ("query"
                                             (:feature
                                              :postmodern-use-mop "table"
                                              "config")))))))
