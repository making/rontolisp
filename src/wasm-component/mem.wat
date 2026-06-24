(module
  (type (;0;) (func (param i32 i32 i32 i32) (result i32)))
  (memory (;0;) 6)
  (global $hp (;0;) (mut i32) i32.const 65536)
  (export "memory" (memory 0))
  (export "cabi_realloc" (func 0))
  (func (;0;) (type 0) (param i32 i32 i32 i32) (result i32)
    (local $r i32)
    global.get $hp
    local.get 2
    i32.const 1
    i32.sub
    i32.add
    local.get 2
    i32.const 1
    i32.sub
    i32.const -1
    i32.xor
    i32.and
    global.set $hp
    global.get $hp
    local.set $r
    global.get $hp
    local.get 3
    i32.add
    global.set $hp
    local.get $r
  )
)
