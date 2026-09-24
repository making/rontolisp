import sys

kind, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
with open(out, "w") as f:
    if kind == "bq":
        f.write("(defun g (x) `(,x " + " ".join(str(i % 1000) for i in range(n)) + "))\n")
        f.write("(print (length (g 1)))\n")
    elif kind == "sym":
        f.write("(defparameter *table* '(" + " ".join("s%d" % (i % 1000) for i in range(n)) + "))\n")
        f.write("(print (length *table*))\n")
    elif kind == "num":
        f.write("(defparameter *table* '(" + " ".join(str(i % 1000) for i in range(n)) + "))\n")
        f.write("(print (length *table*))\n")
