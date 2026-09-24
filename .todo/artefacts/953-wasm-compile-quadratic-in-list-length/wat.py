import sys

# usage: wat.py shape n out.wat [chunk]
shape, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
chunk = int(sys.argv[4]) if len(sys.argv) > 4 else 16
b = []
w = b.append
w("(module")
w("  (rec (type $cons (struct (field eqref) (field eqref))))")
w("  (func $intern (param i32) (result eqref) local.get 0 ref.i31)")
w("  (global $g (mut eqref) (ref.null eq))")
if shape.startswith("funcs"):
    # one helper per run: (param tail) (result list)
    helpers = 0
    k = n
    while k > 0:
        lo = max(0, k - chunk)
        w("  (func $h%d (param eqref) (result eqref)" % helpers)
        for j in range(lo, k):
            w(("i32.const %d call $intern" if shape.endswith("call") else "i32.const %d ref.i31") % j)
        w("local.get 0")
        for j in range(lo, k):
            w("struct.new $cons")
        w("  )")
        helpers += 1
        k = lo
    w("  (func (export \"f\") (result eqref)")
    w("ref.null eq")
    for h in range(helpers):
        w("call $h%d" % h)
    w("  )")
    w(")")
    open(out, "w").write("\n".join(b) + "\n")
    sys.exit(0)
w("  (func (export \"f\") (result eqref) (local $acc eqref)")


def car(k):
    if shape.endswith("call"):
        return "i32.const %d call $intern" % k
    return "i32.const %d ref.i31" % k


if shape.startswith("deep"):
    # every car first, then N struct.new: operand depth N
    for k in range(n):
        w(car(k))
    w("ref.null eq")
    for k in range(n):
        w("struct.new $cons")
elif shape.startswith("local"):
    # from the tail through one local: constant depth
    w("ref.null eq local.set $acc")
    for k in reversed(range(n)):
        w(car(k) + " local.get $acc struct.new $cons local.set $acc")
    w("local.get $acc")
elif shape.startswith("chunk"):
    # chunks of `chunk` cars on the stack, the tail through one local
    w("ref.null eq local.set $acc")
    k = n
    while k > 0:
        lo = max(0, k - chunk)
        for j in range(lo, k):
            w(car(j))
        w("local.get $acc")
        for j in range(lo, k):
            w("struct.new $cons")
        w("local.set $acc")
        k = lo
    w("local.get $acc")
elif shape == "drop":
    for k in range(n):
        w(car(k) + " ref.null eq struct.new $cons drop")
    w("ref.null eq")
elif shape == "ifcall":
    for k in range(n):
        w("i32.const 1 if i32.const %d call $intern global.set $g end" % k)
    w("global.get $g")
elif shape == "i31drop":
    for k in range(n):
        w("i32.const %d ref.i31 drop" % k)
    w("ref.null eq")
elif shape == "globalset":
    for k in range(n):
        w("i32.const %d ref.i31 global.set $g" % k)
    w("global.get $g")
elif shape == "i32":
    w("i32.const 0")
    for k in range(n):
        w("i32.const %d i32.add" % k)
    w("ref.i31")
w("  )")
w(")")
open(out, "w").write("\n".join(b) + "\n")
