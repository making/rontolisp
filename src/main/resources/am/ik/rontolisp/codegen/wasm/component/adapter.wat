(module
  (type (;0;) (func (result i32)))
  (type (;1;) (func (param i32 i32 i32 i32)))
  (type (;2;) (func (param i32)))
  (type (;3;) (func (result i64)))
  (type (;4;) (func (param i32 i32 i32 i32) (result i32)))
  (type (;5;) (func (param i32 i32) (result i32)))
  (type (;6;) (func (param i32 i64 i32) (result i32)))
  (type (;7;) (func (param i32 i32 i32 i32 i32 i64 i64 i32 i32) (result i32)))
  (type (;8;) (func (param i32) (result i32)))
  (import "mem" "memory" (memory (;0;) 4))
  (import "w" "get-stdout" (func $get_stdout (;0;) (type 0)))
  (import "w" "write" (func $write (;1;) (type 1)))
  (import "w" "drop" (func $drop (;2;) (type 2)))
  (import "w" "get-random-u64" (func $rand_u64 (;3;) (type 3)))
  (import "w" "wall-now" (func $wall_now (;4;) (type 2)))
  (import "w" "mono-now" (func $mono_now (;5;) (type 3)))
  (import "w" "get-environment" (func $getenviron (;6;) (type 2)))
  (export "fd_write" (func 7))
  (export "random_get" (func 8))
  (export "clock_time_get" (func 9))
  (export "environ_sizes_get" (func 10))
  (export "environ_get" (func 11))
  (export "fd_read" (func 12))
  (export "path_open" (func 13))
  (export "fd_close" (func 14))
  (func (;7;) (type 4) (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $os i32) (local $i i32) (local $ptr i32) (local $len i32) (local $total i32) (local $base i32)
    call $get_stdout
    local.set $os
    block $done
      loop $l
        local.get $i
        local.get $cnt
        i32.ge_u
        br_if $done
        local.get $iov
        local.get $i
        i32.const 8
        i32.mul
        i32.add
        local.set $base
        local.get $base
        i32.load
        local.set $ptr
        local.get $base
        i32.const 4
        i32.add
        i32.load
        local.set $len
        local.get $os
        local.get $ptr
        local.get $len
        i32.const 256
        call $write
        local.get $total
        local.get $len
        i32.add
        local.set $total
        local.get $i
        i32.const 1
        i32.add
        local.set $i
        br $l
      end
    end
    local.get $os
    call $drop
    local.get $nw
    local.get $total
    i32.store
    i32.const 0
  )
  (func (;8;) (type 5) (param $buf i32) (param $len i32) (result i32)
    (local $i i32)
    block $done
      loop $l
        local.get $i
        local.get $len
        i32.ge_u
        br_if $done
        local.get $buf
        local.get $i
        i32.add
        call $rand_u64
        i64.store
        local.get $i
        i32.const 8
        i32.add
        local.set $i
        br $l
      end
    end
    i32.const 0
  )
  (func (;9;) (type 6) (param $clkid i32) (param $prec i64) (param $resptr i32) (result i32)
    local.get $clkid
    i32.eqz
    if ;; label = @1
      i32.const 512
      call $wall_now
      local.get $resptr
      i32.const 512
      i64.load
      i64.const 1000000000
      i64.mul
      i32.const 520
      i32.load
      i64.extend_i32_u
      i64.add
      i64.store
    else
      local.get $resptr
      call $mono_now
      i64.store
    end
    i32.const 0
  )
  (func (;10;) (type 5) (param $cp i32) (param $bp i32) (result i32)
    (local $base i32) (local $count i32) (local $i i32) (local $sz i32) (local $e i32)
    i32.const 600
    call $getenviron
    i32.const 600
    i32.load
    local.set $base
    i32.const 604
    i32.load
    local.set $count
    block $d
      loop $l
        local.get $i
        local.get $count
        i32.ge_u
        br_if $d
        local.get $base
        local.get $i
        i32.const 16
        i32.mul
        i32.add
        local.set $e
        local.get $sz
        local.get $e
        i32.const 4
        i32.add
        i32.load
        local.get $e
        i32.const 12
        i32.add
        i32.load
        i32.add
        i32.const 2
        i32.add
        i32.add
        local.set $sz
        local.get $i
        i32.const 1
        i32.add
        local.set $i
        br $l
      end
    end
    local.get $cp
    local.get $count
    i32.store
    local.get $bp
    local.get $sz
    i32.store
    i32.const 0
  )
  (func (;11;) (type 5) (param $pp i32) (param $bufp i32) (result i32)
    (local $base i32) (local $count i32) (local $i i32) (local $out i32) (local $e i32) (local $kp i32) (local $kl i32) (local $vp i32) (local $vl i32) (local $j i32)
    i32.const 600
    call $getenviron
    i32.const 600
    i32.load
    local.set $base
    i32.const 604
    i32.load
    local.set $count
    local.get $bufp
    local.set $out
    block $d
      loop $l
        local.get $i
        local.get $count
        i32.ge_u
        br_if $d
        local.get $base
        local.get $i
        i32.const 16
        i32.mul
        i32.add
        local.set $e
        local.get $e
        i32.load
        local.set $kp
        local.get $e
        i32.const 4
        i32.add
        i32.load
        local.set $kl
        local.get $e
        i32.const 8
        i32.add
        i32.load
        local.set $vp
        local.get $e
        i32.const 12
        i32.add
        i32.load
        local.set $vl
        local.get $pp
        local.get $i
        i32.const 4
        i32.mul
        i32.add
        local.get $out
        i32.store
        i32.const 0
        local.set $j
        block $kd
          loop $k
            local.get $j
            local.get $kl
            i32.ge_u
            br_if $kd
            local.get $out
            local.get $kp
            local.get $j
            i32.add
            i32.load8_u
            i32.store8
            local.get $out
            i32.const 1
            i32.add
            local.set $out
            local.get $j
            i32.const 1
            i32.add
            local.set $j
            br $k
          end
        end
        local.get $out
        i32.const 61
        i32.store8
        local.get $out
        i32.const 1
        i32.add
        local.set $out
        i32.const 0
        local.set $j
        block $vd
          loop $v
            local.get $j
            local.get $vl
            i32.ge_u
            br_if $vd
            local.get $out
            local.get $vp
            local.get $j
            i32.add
            i32.load8_u
            i32.store8
            local.get $out
            i32.const 1
            i32.add
            local.set $out
            local.get $j
            i32.const 1
            i32.add
            local.set $j
            br $v
          end
        end
        local.get $out
        i32.const 0
        i32.store8
        local.get $out
        i32.const 1
        i32.add
        local.set $out
        local.get $i
        i32.const 1
        i32.add
        local.set $i
        br $l
      end
    end
    i32.const 0
  )
  (func (;12;) (type 4) (param i32 i32 i32 i32) (result i32)
    i32.const 8
  )
  (func (;13;) (type 7) (param i32 i32 i32 i32 i32 i64 i64 i32 i32) (result i32)
    i32.const 8
  )
  (func (;14;) (type 8) (param i32) (result i32)
    i32.const 0
  )
)
