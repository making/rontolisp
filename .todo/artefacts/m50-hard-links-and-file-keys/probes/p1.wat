;; Preview 1 probe: the filestat of a.txt, of b.txt after path_link(a.txt -> b.txt),
;; and path_link's errno for several cases, each written raw to stdout.
(module
  (import "wasi_snapshot_preview1" "path_filestat_get" (func $stat (param i32 i32 i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "path_link" (func $link (param i32 i32 i32 i32 i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_write" (func $write (param i32 i32 i32 i32) (result i32)))
  (memory (export "memory") 1)
  (data (i32.const 100) "a.txt")      ;; 5
  (data (i32.const 110) "b.txt")      ;; 5
  (data (i32.const 120) "d")          ;; 1
  (data (i32.const 130) "e")          ;; 1
  (data (i32.const 140) "missing")    ;; 7
  (data (i32.const 150) "ro/x.txt")   ;; 8
  (data (i32.const 160) "sl")         ;; 2
  (data (i32.const 170) "f")          ;; 1
  (data (i32.const 180) "a.txt/x")    ;; 7
  (data (i32.const 190) "q.txt")      ;; 5
  (data (i32.const 200) "dang")       ;; 4
  (data (i32.const 210) "g")          ;; 1
  (data (i32.const 220) "../x.txt")   ;; 8
  (data (i32.const 230) "")           ;; 0
  (func $out (param $ptr i32) (param $len i32)
    (i32.store (i32.const 0) (local.get $ptr))
    (i32.store (i32.const 4) (local.get $len))
    (drop (call $write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8))))
  (func $st (param $fd i32) (param $flags i32) (param $p i32) (param $l i32)
    (i32.store8 (i32.const 1000) (call $stat (local.get $fd) (local.get $flags) (local.get $p) (local.get $l) (i32.const 1008)))
    (call $out (i32.const 1000) (i32.const 72)))
  (func $ln (param $ofd i32) (param $flags i32) (param $op i32) (param $ol i32) (param $nfd i32) (param $np i32) (param $nl i32)
    (i32.store8 (i32.const 1000) (call $link (local.get $ofd) (local.get $flags) (local.get $op) (local.get $ol) (local.get $nfd) (local.get $np) (local.get $nl)))
    (call $out (i32.const 1000) (i32.const 1)))
  (func (export "_start")
    (call $st (i32.const 3) (i32.const 1) (i32.const 100) (i32.const 5))   ;; a.txt
    (call $ln (i32.const 3) (i32.const 0) (i32.const 100) (i32.const 5) (i32.const 3) (i32.const 110) (i32.const 5)) ;; a -> b
    (call $st (i32.const 3) (i32.const 1) (i32.const 110) (i32.const 5))   ;; b.txt
    (call $st (i32.const 3) (i32.const 1) (i32.const 120) (i32.const 1))   ;; d
    (call $ln (i32.const 3) (i32.const 0) (i32.const 100) (i32.const 5) (i32.const 3) (i32.const 110) (i32.const 5)) ;; again: exist
    (call $ln (i32.const 3) (i32.const 0) (i32.const 140) (i32.const 7) (i32.const 3) (i32.const 190) (i32.const 5)) ;; missing -> q
    (call $ln (i32.const 3) (i32.const 0) (i32.const 100) (i32.const 5) (i32.const 3) (i32.const 150) (i32.const 8)) ;; ro/x
    (call $ln (i32.const 3) (i32.const 0) (i32.const 120) (i32.const 1) (i32.const 3) (i32.const 130) (i32.const 1)) ;; dir
    (call $ln (i32.const 3) (i32.const 0) (i32.const 160) (i32.const 2) (i32.const 3) (i32.const 170) (i32.const 1)) ;; symlink, no follow
    (call $ln (i32.const 3) (i32.const 0) (i32.const 100) (i32.const 5) (i32.const 3) (i32.const 180) (i32.const 7)) ;; under file
    (call $ln (i32.const 3) (i32.const 0) (i32.const 200) (i32.const 4) (i32.const 3) (i32.const 210) (i32.const 1)) ;; dangling
    (call $ln (i32.const 3) (i32.const 0) (i32.const 100) (i32.const 5) (i32.const 3) (i32.const 220) (i32.const 8)) ;; above preopen
    (call $ln (i32.const 3) (i32.const 0) (i32.const 100) (i32.const 5) (i32.const 4) (i32.const 110) (i32.const 5)) ;; other preopen
    (call $ln (i32.const 3) (i32.const 0) (i32.const 140) (i32.const 7) (i32.const 3) (i32.const 110) (i32.const 5)) ;; missing -> existing b
    (call $ln (i32.const 3) (i32.const 0) (i32.const 120) (i32.const 1) (i32.const 3) (i32.const 110) (i32.const 5)) ;; dir -> existing b
    (call $ln (i32.const 3) (i32.const 0) (i32.const 100) (i32.const 5) (i32.const 3) (i32.const 230) (i32.const 0)) ;; empty link
    (call $ln (i32.const 3) (i32.const 0) (i32.const 230) (i32.const 0) (i32.const 3) (i32.const 190) (i32.const 5)) ;; empty target
    (call $ln (i32.const 3) (i32.const 1) (i32.const 160) (i32.const 2) (i32.const 3) (i32.const 190) (i32.const 5)) ;; symlink followed
    (call $st (i32.const 3) (i32.const 0) (i32.const 170) (i32.const 1))   ;; f lstat
    (call $st (i32.const 3) (i32.const 0) (i32.const 160) (i32.const 2))   ;; sl lstat
  ))
