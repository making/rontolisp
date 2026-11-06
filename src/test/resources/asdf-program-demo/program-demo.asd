;; The AsdfProgramOpE2eTest fixtures: systems that say they are an executable,
;; clingon.intro's program-op trio, and one that is a library.
(defsystem "program-demo"
  :build-operation "program-op"
  :build-pathname "bin/program-demo"
  :entry-point "program-demo:main"
  :components ((:file "program-demo")))

(defsystem "program-demo/library"
  :components ((:file "program-demo")))
