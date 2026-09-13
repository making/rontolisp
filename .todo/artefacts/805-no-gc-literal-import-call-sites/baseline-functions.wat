  (func (;4;) (type 0) (result i64)
    i32.const 8
    call 8
    drop
    i32.const 60
    i32.const 76
    call 9
    drop
    i32.const 60
    i32.const 104
    call 10
    drop
    i32.const 116
    i32.const 132
    call 9
  )
  (func (;5;) (type 1) (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.add
  )
  (func (;6;) (type 2) (param i64) (result i64)
    i32.const 232
    call 8
    drop
    local.get 0
    call 11
  )
  (func (;7;) (type 3) (param i64) (result i64)
    local.get 0
    i64.const 1
    i64.eq
    i64.extend_i32_s
    i64.const 0
    i64.ne
    if (result i64) ;; label = @1
      i32.const 284
      i32.const 300
      call 12
    else
      local.get 0
      i64.const 2
      i64.eq
      i64.extend_i32_s
      i64.const 0
      i64.ne
      if (result i64) ;; label = @2
        i32.const 284
        i32.const 368
        call 12
      else
        i64.const 1
        i64.const 0
        i64.ne
        if (result i64) ;; label = @3
          i32.const 284
          i32.const 444
          call 12
        else
          i64.const 0
        end
      end
    end
  )
  (func (;8;) (type 4) (param i32) (result i64)
    local.get 0
    call 13
  )
  (func (;9;) (type 5) (param i32 i32) (result i64)
    local.get 0
    local.get 1
    call 14
  )
  (func (;10;) (type 6) (param i32 i32) (result i64)
    local.get 0
    local.get 1
    call 15
  )
  (func (;11;) (type 7) (param i64) (result i64)
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
      call 11
      local.get 0
      i64.const 2
      i64.sub
      call 11
      i64.add
    end
  )
  (func (;12;) (type 8) (param i32 i32) (result i64)
    local.get 0
    local.get 1
    call 16
  )
  (func (;13;) (type 9) (param i32) (result i64)
    local.get 0
    i32.const 4
    i32.add
    local.get 0
    i32.load
    call 0
    i64.const 0
  )
  (func (;14;) (type 10) (param i32 i32) (result i64)
    local.get 0
    i32.const 4
    i32.add
    local.get 0
    i32.load
    local.get 1
    i32.const 4
    i32.add
    local.get 1
    i32.load
    call 1
    i64.const 0
  )
  (func (;15;) (type 11) (param i32 i32) (result i64)
    local.get 0
    i32.const 4
    i32.add
    local.get 0
    i32.load
    local.get 1
    i32.const 4
    i32.add
    local.get 1
    i32.load
    call 3
    i64.const 0
  )
  (func (;16;) (type 12) (param i32 i32) (result i64)
    local.get 0
    i32.const 4
    i32.add
    local.get 0
    i32.load
    local.get 1
    i32.const 4
    i32.add
    local.get 1
    i32.load
    call 2
    i64.const 0
  )
  (func (;17;) (type 13)
    (local i32)
    global.get 0
    local.set 0
    call 4
    drop
    local.get 0
    global.set 0
  )
  (func (;18;) (type 14) (param i32 i32) (result i32)
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
  (func (;19;) (type 15) (param i32) (result i32)
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
  (func (;20;) (type 16) (param i32)
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
  (func (;21;) (type 17) (param i32) (result i32)
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
  (func (;22;) (type 18) (result i32)
    global.get 0
  )
  (func (;23;) (type 19) (param i32)
    local.get 0
    global.set 0
  )
