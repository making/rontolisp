;; Fixup module for the --no-gc --component print micro-adapter: instantiated
;; LAST, its active element segment writes the bridge's real fd_write
;; (bridge-nogc-print.wat) into slot 0 of the shim's table (shim-nogc-print.wat), closing
;; the shim indirection. Both imports arrive under the empty instance name, grouped by
;; NoGcWasmComponentBuilder from the shim's exported table and the bridge's fd_write.
(module
  (type $fdw (func (param i32 i32 i32 i32) (result i32)))
  (import "" "fd_write" (func (type $fdw)))
  (import "" "$imports" (table 1 1 funcref))
  (elem (i32.const 0) func 0))
