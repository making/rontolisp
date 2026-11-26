(module
  ;; Shared memory + the canonical-ABI allocator of a (non-serve) rontolisp component.
  ;;
  ;; cabi_realloc bumps the rontolisp core's OWN heap pointer (the HEAP_PTR cell at
  ;; linear address 84) instead of keeping a private bump region: the component's one
  ;; memory has several writers (this allocator, the preview1 adapter's page-5 scratch,
  ;; the core's static data / intern pool / heap), and a private region starting at a
  ;; fixed address collides with the core's static data as soon as the program is big
  ;; enough to reach it. Advancing HEAP_PTR is a PERMANENT allocation in the core's
  ;; discipline (the core's transient string scratch always sits above HEAP_PTR and
  ;; pops back), so host-lifted buffers can never be overwritten by core activity --
  ;; the same contract the core's own __ronto_alloc and (on wasm-export modules) its
  ;; appended cabi_realloc already follow. The old/old-size params are ignored like
  ;; before (a UTF-8 guest is only ever asked for fresh exact-size allocations).
  ;;
  ;; The cell is zero until the core module's data segments install the program's
  ;; heapBase at instantiation; canonical calls only happen after instantiation, so
  ;; the allocator never runs against the uninitialized cell.
  (type (;0;) (func (param i32 i32 i32 i32) (result i32)))
  (memory (;0;) 6)
  (export "memory" (memory 0))
  (export "cabi_realloc" (func 0))
  (func (;0;) (type 0) (param i32 i32 i32 i32) (result i32)
    (local $r i32)
    ;; r = (mem[84] + align-1) & ~(align-1)
    (local.set $r
      (i32.and
        (i32.add (i32.load (i32.const 84)) (i32.sub (local.get 2) (i32.const 1)))
        (i32.xor (i32.sub (local.get 2) (i32.const 1)) (i32.const -1))))
    ;; mem[84] = (r + size + 7) & ~7 -- keep HEAP_PTR 8-aligned like __ronto_alloc
    (i32.store (i32.const 84)
      (i32.and
        (i32.add (i32.add (local.get $r) (local.get 3)) (i32.const 7))
        (i32.const -8)))
    ;; grow so [r, r+size) is in-bounds before the host writes into it
    (if (i32.gt_u
          (i32.shr_u (i32.add (i32.add (local.get $r) (local.get 3)) (i32.const 0xffff))
            (i32.const 16))
          (memory.size))
      (then
        (drop (memory.grow
          (i32.sub
            (i32.shr_u (i32.add (i32.add (local.get $r) (local.get 3)) (i32.const 0xffff))
              (i32.const 16))
            (memory.size))))))
    (local.get $r))
)
