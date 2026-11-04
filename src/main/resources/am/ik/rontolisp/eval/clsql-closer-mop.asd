;;;; A replacement for upstream clsql's clsql.asd (eval.AsdOverrides): the same
;;;; components over closer-mop, which eval.ClsqlForms makes clsql-sys use.
;;;;
;;;; The real file loads uffi only for a perform method reading ~/.clsql-init.lisp,
;;;; and a .asd's methods do not run here, so the core needs no foreign function
;;;; interface. A database backend system still depends on uffi itself.

(defsystem clsql
  :name "CLSQL"
  :author "Kevin Rosenberg <kevin@rosenberg.net>"
  :maintainer "Kevin M. Rosenberg <kmr@debian.org>"
  :licence "Lessor Lisp General Public License"
  :description "Common Lisp SQL Interface library"
  :version "6.4"
  :depends-on (closer-mop)
  :components ((:module sql
                        :components ((:module base
                                              :pathname ""
                                              :components
                                              ((:file "cmucl-compat")
                                               (:file "package")
                                               (:file "kmr-mop"
                                                      :depends-on ("package"))
                                               (:file "base-classes"
                                                      :depends-on ("package"))
                                               (:file "conditions"
                                                :depends-on ("base-classes"))
                                               (:file "db-interface"
                                                :depends-on ("conditions"))
                                               (:file "decimals"
                                                      :depends-on
                                                      ("package"
                                                       "db-interface"))
                                               (:file "utils"
                                                      :depends-on
                                                      ("package"
                                                       "db-interface"))
                                               (:file "time"
                                                      :depends-on
                                                      ("package" "conditions"
                                                       "utils"))
                                               (:file "generics"
                                                      :depends-on ("package"))))
                                     (:module database
                                              :pathname ""
                                              :components
                                              ((:file "initialize")
                                               (:file "database"
                                                :depends-on ("initialize"))
                                               (:file "recording"
                                                      :depends-on ("database"))
                                               (:file "pool"
                                                      :depends-on ("database")))
                                              :depends-on (base))
                                     (:module syntax
                                              :pathname ""
                                              :components
                                              ((:file "expressions")
                                               (:file "operations"
                                                :depends-on ("expressions"))
                                               (:file "syntax"
                                                :depends-on ("operations")))
                                              :depends-on (database))
                                     (:module functional
                                              :pathname ""
                                              :components
                                              ((:file "fdml")
                                               (:file "transaction"
                                                      :depends-on ("fdml"))
                                               (:file "loop-extension"
                                                      :depends-on ("fdml"))
                                               (:file "fddl"
                                                      :depends-on ("fdml")))
                                              :depends-on (syntax))
                                     (:module object
                                              :pathname ""
                                              :components
                                              ((:file "metaclasses")
                                               (:file "ooddl"
                                                :depends-on ("metaclasses"))
                                               (:file "oodml"
                                                      :depends-on ("ooddl")))
                                              :depends-on (functional))
                                     (:module generic
                                              :pathname ""
                                              :components
                                              ((:file "generic-postgresql")
                                               (:file "generic-odbc")
                                               (:file "sequences")
                                               (:file "command-object"))
                                              :depends-on (functional)))))
  :in-order-to ((test-op (test-op "clsql-tests"))))
