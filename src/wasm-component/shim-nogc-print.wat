;; Funcref-table shim for the --no-gc --component print micro-adapter (todo 93), breaking
;; the instantiation cycle the wit-component adapter pattern solves the same way: the
;; rontolisp core imports wasi_snapshot_preview1.fd_write from the bridge, but the bridge
;; (bridge-nogc-print.wat) must read the iovec out of the CORE's memory -- so neither can
;; be instantiated first. This shim is instantiated before both: its fd_write forwards
;; through table slot 0, the core instantiates against it (keeping the core module
;; byte-identical to the plain --no-gc output), and after the bridge exists,
;; fixup-nogc-print.wat fills the slot with the real fd_write.
(module
  (type $fdw (func (param i32 i32 i32 i32) (result i32)))
  (table (export "$imports") 1 1 funcref)
  (func (export "fd_write") (type $fdw)
    local.get 0
    local.get 1
    local.get 2
    local.get 3
    i32.const 0
    call_indirect (type $fdw)))
