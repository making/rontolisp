;; The core module of the test's host of wasi:logging (host.wit): log writes
;; "[level] context: message" and a line's end on the standard error, the level by its
;; WIT name. Its imports and exports are named as wasm-tools' component new names a
;; world's (the canonical ABI's flattening: an enum an i32, a string a pointer and a
;; length).
(module
  (import "wasi:cli/stderr@0.2.0" "get-stderr" (func $get_stderr (result i32)))
  (import "wasi:io/streams@0.2.0" "[method]output-stream.blocking-write-and-flush"
    (func $write (param i32 i32 i32 i32)))
  (memory (export "memory") 1)
  ;; the levels' names, eight bytes apart; the result of a write at 1016; the heap after
  (data (i32.const 0) "trace\00\00\00debug\00\00\00info\00\00\00\00warn\00\00\00\00error\00\00\00critical")
  (global $heap (mut i32) (i32.const 1024))
  (global $stream (mut i32) (i32.const -1))
  ;; a bump allocator: the canonical ABI's realloc, never asked to grow a block in place
  (func $alloc (export "cabi_realloc") (param i32 i32) (param $align i32) (param $size i32) (result i32)
    (local $at i32)
    (local.set $at (i32.and (i32.add (global.get $heap) (i32.sub (local.get $align) (i32.const 1)))
                            (i32.sub (i32.const 0) (local.get $align))))
    (global.set $heap (i32.add (local.get $at) (local.get $size)))
    (block $fits
      (loop $grow
        (br_if $fits (i32.le_u (global.get $heap) (i32.mul (memory.size) (i32.const 65536))))
        (drop (memory.grow (i32.const 1)))
        (br $grow)))
    (local.get $at))
  ;; copies the bytes to the end of a line being made, answering its new end
  (func $append (param $to i32) (param $from i32) (param $count i32) (result i32)
    (memory.copy (local.get $to) (local.get $from) (local.get $count))
    (i32.add (local.get $to) (local.get $count)))
  ;; the length of a level's name: up to its first zero byte, eight at most
  (func $name_length (param $name i32) (result i32)
    (local $n i32)
    (block $done
      (loop $next
        (br_if $done (i32.ge_u (local.get $n) (i32.const 8)))
        (br_if $done (i32.eqz (i32.load8_u (i32.add (local.get $name) (local.get $n)))))
        (local.set $n (i32.add (local.get $n) (i32.const 1)))
        (br $next)))
    (local.get $n))
  (func (export "wasi:logging/logging@0.1.0-draft#log")
    (param $level i32) (param $context i32) (param $context_length i32)
    (param $message i32) (param $message_length i32)
    (local $line i32) (local $end i32) (local $name i32)
    (if (i32.lt_s (global.get $stream) (i32.const 0))
      (then (global.set $stream (call $get_stderr))))
    (local.set $line (call $alloc (i32.const 0) (i32.const 0) (i32.const 1)
      (i32.add (i32.add (local.get $context_length) (local.get $message_length)) (i32.const 16))))
    (i32.store8 (local.get $line) (i32.const 91))
    (local.set $name (i32.mul (local.get $level) (i32.const 8)))
    (local.set $end (call $append (i32.add (local.get $line) (i32.const 1)) (local.get $name)
      (call $name_length (local.get $name))))
    (i32.store16 (local.get $end) (i32.const 0x205d))
    (local.set $end (call $append (i32.add (local.get $end) (i32.const 2)) (local.get $context)
      (local.get $context_length)))
    (i32.store16 (local.get $end) (i32.const 0x203a))
    (local.set $end (call $append (i32.add (local.get $end) (i32.const 2)) (local.get $message)
      (local.get $message_length)))
    (i32.store8 (local.get $end) (i32.const 10))
    (call $write (global.get $stream) (local.get $line)
      (i32.sub (i32.add (local.get $end) (i32.const 1)) (local.get $line)) (i32.const 1016))))
