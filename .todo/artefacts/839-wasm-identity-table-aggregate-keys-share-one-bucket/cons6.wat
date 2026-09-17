(module
  (rec
    (type $cons (struct (field (mut (ref null eq))) (field (mut (ref null eq))) (field (mut i32)) (field (mut i32)) (field (mut i32)) (field (mut i32)))))
  (global $head (mut (ref null eq)) (ref.null eq))
  (func (export "alloc") (param $n i32) (result i32)
    (local $i i32)
    (local.set $i (i32.const 0))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (local.get $n)))
        (global.set $head (struct.new $cons (ref.null eq) (global.get $head) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (local.get $i)))
