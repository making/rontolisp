;; An unhandled error ends the module with a trap: the stub exits 134 like `wasmtime run`.
(print 1)
(error "boom ~a" 42)
(print 2)
