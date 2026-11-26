;; A binding the way osicat writes one: constants and a structure layout grovelled
;; from the headers, and C wrappers compiled into a library of the system's own.
(defsystem "grovel-demo"
  :defsystem-depends-on ("cffi-grovel")
  :depends-on ("cffi")
  :serial t
  :components ((:file "package")
               (:cffi-grovel-file "constants")
               (:cffi-wrapper-file "wrappers" :soname "libgroveldemo")
               (:file "demo")))
