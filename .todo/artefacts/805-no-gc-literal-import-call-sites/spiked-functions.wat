  (func (;4;) (type 0) (result i64)
    i32.const 12
    i32.const 47
    call 0
    i64.const 0
    drop
    i32.const 64
    i32.const 12
    i32.const 80
    i32.const 21
    call 1
    i64.const 0
    drop
    i32.const 64
    i32.const 12
    i32.const 108
    i32.const 7
    call 3
    i64.const 0
    drop
    i32.const 120
    i32.const 11
    i32.const 136
    i32.const 93
    call 1
    i64.const 0
  )
  (func (;5;) (type 1) (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.add
  )
  (func (;6;) (type 2) (param i64) (result i64)
    i32.const 236
    i32.const 46
    call 0
    i64.const 0
    drop
    local.get 0
    call 8
  )
  (func (;7;) (type 3) (param i64) (result i64)
    local.get 0
    i64.const 1
    i64.eq
    i64.extend_i32_s
    i64.const 0
    i64.ne
    if (result i64) ;; label = @1
      i32.const 288
      i32.const 12
      i32.const 304
      i32.const 61
      call 2
      i64.const 0
    else
      local.get 0
      i64.const 2
      i64.eq
      i64.extend_i32_s
      i64.const 0
      i64.ne
      if (result i64) ;; label = @2
        i32.const 288
        i32.const 12
        i32.const 372
        i32.const 72
        call 2
        i64.const 0
      else
        i64.const 1
        i64.const 0
        i64.ne
        if (result i64) ;; label = @3
          i32.const 288
          i32.const 12
          i32.const 448
          i32.const 51
          call 2
          i64.const 0
        else
          i64.const 0
        end
      end
    end
  )
  (func (;8;) (type 4) (param i64) (result i64)
    local.get 0
    i64.const 1
    i64.le_s
    i64.extend_i32_s
    i64.const 0
    i64.ne
    if (result i64) ;; label = @1
      local.get 0
    else
      local.get 0
      i64.const 1
      i64.sub
      call 8
      local.get 0
      i64.const 2
      i64.sub
      call 8
      i64.add
    end
  )
  (func (;9;) (type 5)
    (local i32)
    global.get 0
    local.set 0
    call 4
    drop
    local.get 0
    global.set 0
  )
  (func (;10;) (type 6) (param i32 i32) (result i32)
    (local i32 i64)
    global.get 0
    local.set 2
    local.get 0
    i64.extend_i32_s
    local.get 1
    i64.extend_i32_s
    call 5
    local.set 3
    local.get 3
    i64.const -2147483648
    i64.lt_s
    if ;; label = @1
      unreachable
    end
    local.get 3
    i64.const 2147483647
    i64.gt_s
    if ;; label = @1
      unreachable
    end
    local.get 3
    i32.wrap_i64
    local.get 2
    global.set 0
  )
  (func (;11;) (type 7) (param i32) (result i32)
    (local i32 i64)
    global.get 0
    local.set 1
    local.get 0
    i64.extend_i32_s
    call 6
    local.set 2
    local.get 2
    i64.const -2147483648
    i64.lt_s
    if ;; label = @1
      unreachable
    end
    local.get 2
    i64.const 2147483647
    i64.gt_s
    if ;; label = @1
      unreachable
    end
    local.get 2
    i32.wrap_i64
    local.get 1
    global.set 0
  )
  (func (;12;) (type 8) (param i32)
    (local i32)
    global.get 0
    local.set 1
    local.get 0
    i64.extend_i32_s
    call 7
    drop
    local.get 1
    global.set 0
  )
  (func (;13;) (type 9) (param i32) (result i32)
    (local i32 i32 i32)
    global.get 0
    local.set 1
    local.get 1
    local.get 0
    i32.add
    i32.const 3
    i32.add
    i32.const -4
    i32.and
    local.set 2
    local.get 2
    global.set 0
    local.get 2
    i32.const 65535
    i32.add
    i32.const 16
    i32.shr_u
    local.set 3
    local.get 3
    memory.size
    i32.gt_s
    if ;; label = @1
      local.get 3
      memory.size
      i32.sub
      memory.grow
      drop
    end
    local.get 1
  )
  (func (;14;) (type 10) (result i32)
    global.get 0
  )
  (func (;15;) (type 11) (param i32)
    local.get 0
    global.set 0
  )
