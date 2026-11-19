# Writes a{n}_l{l}.lisp: an async-defun of n awaits in sequence with l locals read after
# the last. Compile each with
#   java -Drontolisp.wasm.debug-func-sizes=true -jar rontolisp-exec.jar aN_lL.lisp -o x.wasm --component
# and read the one _async_resume_ line: its bytes by n and l are the cost of a suspend site.
def prog(n_awaits, n_locals, name):
    locs = ' '.join(f'(v{i} (f {i}))' for i in range(n_locals))
    body = ' '.join(f'(setq acc (+ acc (rontolisp:await (g {a}))))' for a in range(n_awaits))
    use = ' '.join(f'v{i}' for i in range(n_locals)) or '0'
    open(name, 'w').write(f'''(defvar *fut* (rontolisp::%future-new))
(defun f (x) (+ x 1))
(rontolisp:async-defun g (x) (rontolisp:await *fut*) x)
(rontolisp:async-defun h ()
  (let ((acc 0) {locs})
    {body}
    (print (+ acc {use}))))
(h)
(rontolisp::%future-settle *fut* 1)
''')

for n, l in [(1, 0), (11, 0), (21, 0), (11, 10), (21, 10), (11, 20), (21, 20)]:
    prog(n, l, f'a{n}_l{l}.lisp')
