(module
  (type (;0;) (func (param i32 i32 i32 i32) (result i32)))
  (memory (;0;) 16)
  (export "memory" (memory 0))
  (export "cabi_realloc" (func 0))
  ;; The canonical-ABI bump pointer lives in a linear-memory CELL at 0x10000, NOT a wasm
  ;; global, so the rontolisp core (which imports this memory) can reset it to the base at
  ;; the top of each wasi:http/incoming-handler call. cabi_realloc is where the host writes
  ;; a request's result buffers (the incoming path / headers / body it hands back), and it
  ;; only ever grows; an instance-reusing host (jco, wasmCloud) calls handle many times on
  ;; one instance, so without the per-request reset linear memory grows by ~one request's
  ;; worth every call. wasmtime serve re-instantiates per request and so never showed it.
  ;; The reset is a plain `i32.store` the core emits in the handle wrapper (see
  ;; WasmExportCompiler / WasmLispCompiler.CABI_HP_CELL_ADDR); a memory cell needs no global
  ;; import (which would shift the core's global index space) and no adapter.
  ;;
  ;; Allocations start at 0x10008, just above the 8-byte cell. Seeded here so the cell is
  ;; valid even if something allocates before the first handle call (nothing does today).
  (data (i32.const 0x10000) "\08\00\01\00")   ;; mem[0x10000] = 0x10008
  (func (;0;) (type 0) (param i32 i32 i32 i32) (result i32)
    (local $p i32)
    ;; p = align_up(mem[0x10000], align)  -- align is a power of two
    (local.set $p
      (i32.and
        (i32.add (i32.load (i32.const 0x10000))
                 (i32.sub (local.get 2) (i32.const 1)))
        (i32.xor (i32.sub (local.get 2) (i32.const 1)) (i32.const -1))))
    ;; mem[0x10000] = p + new_size ; return p
    (i32.store (i32.const 0x10000) (i32.add (local.get $p) (local.get 3)))
    (local.get $p)
  )
)
