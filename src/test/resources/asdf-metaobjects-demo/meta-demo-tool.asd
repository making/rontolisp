;; Loaded by meta-demo.asd itself, while that file is read (fast-io-test.asd
;; does the same with fiveam). A system of its own .asd: real ASDF refuses to
;; load one the reading .asd defines (a circular dependency on its definition).
(defsystem "meta-demo-tool"
  :components ((:file "tool")))
