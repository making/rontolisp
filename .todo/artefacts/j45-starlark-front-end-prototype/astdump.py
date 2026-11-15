#!/usr/bin/env python3
"""Python's `ast` of Starlark source, printed as the S-expressions StarlarkTrees prints.

    astdump.py OUT            split $SUITE's chunks into OUT/chunks/*.star, dump each to OUT/py/
    astdump.py --compare OUT  compare OUT/py/ with OUT/java/ (the Java side's dumps)

Names and literals carry @line:column (column in UTF-16 units, 1-based). A construct with
no Starlark counterpart prints as (unsupported Kind); a SyntaxError as ERROR line:col msg.
"""
import ast, glob, os, re, sys, warnings
warnings.simplefilter('ignore')

BINOPS = {ast.Add: '+', ast.Sub: '-', ast.Mult: '*', ast.Div: '/', ast.FloorDiv: '//', ast.Mod: '%',
          ast.BitOr: '|', ast.BitAnd: '&', ast.BitXor: '^', ast.LShift: '<<', ast.RShift: '>>'}
CMPOPS = {ast.Eq: '==', ast.NotEq: '!=', ast.Lt: '<', ast.LtE: '<=', ast.Gt: '>', ast.GtE: '>=',
          ast.In: 'in', ast.NotIn: 'not-in'}
UNOPS = {ast.Not: 'not', ast.USub: '-', ast.UAdd: '+', ast.Invert: '~'}


def quote(s, printable):
    out = []
    for c in s:
        o = ord(c)
        out.append({'\\': '\\\\', '"': '\\"', '\n': '\\n', '\t': '\\t', '\r': '\\r'}.get(c)
                   or (c if printable(o) else '\\x%02x' % o))
    return '"' + ''.join(out) + '"'


class Dumper:
    def __init__(self, source):
        self.lines = source.split('\n')

    def at(self, node):
        line = self.lines[node.lineno - 1].encode('utf-8')[:node.col_offset].decode('utf-8')
        return f' @{node.lineno}:{len(line.encode("utf-16-le")) // 2 + 1}'

    def leaf(self, text, node):
        return f'({text}{self.at(node)})'

    def body(self, stmts):
        return ''.join(' ' + self.stmt(s) for s in stmts)

    def stmt(self, s):
        if isinstance(s, ast.Expr):
            v = s.value
            if isinstance(v, ast.Call) and isinstance(v.func, ast.Name) and v.func.id == 'load':
                return self.load(v)
            return f'(expr {self.expr(v)})'
        if isinstance(s, ast.Assign):
            if len(s.targets) != 1:
                return '(unsupported ChainedAssign)'
            return f'(= {self.expr(s.targets[0])} {self.expr(s.value)})'
        if isinstance(s, ast.AugAssign):
            return f'({BINOPS.get(type(s.op), "?")}= {self.expr(s.target)} {self.expr(s.value)})'
        if isinstance(s, ast.FunctionDef):
            if s.decorator_list or s.returns:
                return '(unsupported Decorated)'
            return f'(def {s.name} {self.params(s.args)}{self.body(s.body)})'
        if isinstance(s, ast.If):
            return f'(if {self.expr(s.test)} ({self.body(s.body)[1:]}) ({self.body(s.orelse)[1:]}))'
        if isinstance(s, ast.For):
            if s.orelse:
                return '(unsupported ForElse)'
            return f'(for {self.expr(s.target)} {self.expr(s.iter)}{self.body(s.body)})'
        if isinstance(s, ast.Return):
            return '(return)' if s.value is None else f'(return {self.expr(s.value)})'
        if isinstance(s, ast.Break):
            return '(break)'
        if isinstance(s, ast.Continue):
            return '(continue)'
        if isinstance(s, ast.Pass):
            return '(pass)'
        return f'(unsupported {type(s).__name__})'

    def load(self, call):
        args = call.args
        if not args or not all(isinstance(a, ast.Constant) and isinstance(a.value, str) for a in args):
            return '(unsupported Load)'
        out = f'(load {quote(args[0].value, lambda o: o >= 0x20 and o != 0x7f)}'
        for a in args[1:]:
            out += f' ({a.value} {quote(a.value, lambda o: True)})'
        for k in call.keywords:
            out += f' ({k.arg} {quote(k.value.value, lambda o: True)})'
        return out + ')'

    def params(self, a):
        out = []
        pos = a.posonlyargs + a.args
        if a.posonlyargs:
            out.append('(unsupported PositionalOnly)')
        nreq = len(pos) - len(a.defaults)
        for i, p in enumerate(pos):
            if p.annotation:
                out.append('(unsupported Annotation)')
            out.append(p.arg if i < nreq else f'(= {p.arg} {self.expr(a.defaults[i - nreq])})')
        if a.vararg:
            out.append(f'(* {a.vararg.arg})')
        elif a.kwonlyargs:
            out.append('*')
        for p, d in zip(a.kwonlyargs, a.kw_defaults):
            out.append(p.arg if d is None else f'(= {p.arg} {self.expr(d)})')
        if a.kwarg:
            out.append(f'(** {a.kwarg.arg})')
        return '(' + ' '.join(out) + ')'

    def expr(self, e):
        if e is None:
            return '_'
        if isinstance(e, ast.Name):
            return self.leaf(f'name {e.id}', e)
        if isinstance(e, ast.Constant):
            v = e.value
            if v is None or isinstance(v, bool):
                return self.leaf(f'name {v}', e)
            if isinstance(v, int):
                return self.leaf(f'int {v}', e)
            if isinstance(v, float):
                return self.leaf(f'float {v!r}', e)
            if isinstance(v, str):
                return self.leaf('string ' + quote(v, lambda o: o >= 0x20 and o != 0x7f), e)
            if isinstance(v, bytes):
                return self.leaf('bytes ' + quote(v.decode('latin-1'), lambda o: 0x20 <= o < 0x7f), e)
            return f'(unsupported {type(v).__name__})'
        if isinstance(e, ast.BinOp):
            op = BINOPS.get(type(e.op))
            if op is None:
                return f'(unsupported {type(e.op).__name__})'
            return f'({op} {self.expr(e.left)} {self.expr(e.right)})'
        if isinstance(e, ast.UnaryOp):
            return f'({UNOPS[type(e.op)]} {self.expr(e.operand)})'
        if isinstance(e, ast.BoolOp):
            op = 'and' if isinstance(e.op, ast.And) else 'or'
            out = self.expr(e.values[0])
            for v in e.values[1:]:
                out = f'({op} {out} {self.expr(v)})'
            return out
        if isinstance(e, ast.Compare):
            if len(e.ops) != 1:
                return '(unsupported ChainedComparison)'
            op = CMPOPS.get(type(e.ops[0]))
            if op is None:
                return f'(unsupported {type(e.ops[0]).__name__})'
            return f'({op} {self.expr(e.left)} {self.expr(e.comparators[0])})'
        if isinstance(e, ast.IfExp):
            return f'(if-expr {self.expr(e.test)} {self.expr(e.body)} {self.expr(e.orelse)})'
        if isinstance(e, ast.Lambda):
            return f'(lambda {self.params(e.args)} {self.expr(e.body)})'
        if isinstance(e, (ast.List, ast.Tuple)):
            name = 'list' if isinstance(e, ast.List) else 'tuple'
            return f'({name}' + ''.join(' ' + self.expr(x) for x in e.elts) + ')'
        if isinstance(e, ast.Dict):
            if any(k is None for k in e.keys):
                return '(unsupported DictUnpack)'
            return '(dict' + ''.join(f' (: {self.expr(k)} {self.expr(v)})' for k, v in zip(e.keys, e.values)) + ')'
        if isinstance(e, (ast.ListComp, ast.DictComp)):
            head = (f'(list-comp {self.expr(e.elt)}' if isinstance(e, ast.ListComp)
                    else f'(dict-comp (: {self.expr(e.key)} {self.expr(e.value)})')
            for g in e.generators:
                head += f' (for {self.expr(g.target)} {self.expr(g.iter)})'
                head += ''.join(f' (if {self.expr(c)})' for c in g.ifs)
            return head + ')'
        if isinstance(e, ast.Call):
            # Starlark's order: positional, keyword, *args, **kwargs.
            args = [self.expr(a) for a in e.args if not isinstance(a, ast.Starred)]
            args += [f'(= {k.arg} {self.expr(k.value)})' for k in e.keywords if k.arg is not None]
            args += [f'(* {self.expr(a.value)})' for a in e.args if isinstance(a, ast.Starred)]
            args += [f'(** {self.expr(k.value)})' for k in e.keywords if k.arg is None]
            return f'(call {self.expr(e.func)}' + ''.join(' ' + a for a in args) + ')'
        if isinstance(e, ast.Attribute):
            return f'(dot {self.expr(e.value)} {e.attr})'
        if isinstance(e, ast.Subscript):
            s = e.slice
            if isinstance(s, ast.Slice):
                return f'(slice {self.expr(e.value)} {self.expr(s.lower)} {self.expr(s.upper)} {self.expr(s.step)})'
            return f'(index {self.expr(e.value)} {self.expr(s)})'
        return f'(unsupported {type(e).__name__})'


def dump(source):
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        return f'ERROR {e.lineno}:{e.offset} {e.msg}'
    d = Dumper(source)
    return '\n'.join(d.stmt(s) for s in tree.body)


def chunks(path):
    """The chunks of a corpus file, as runsuite.py splits them."""
    code = []
    for line in open(path, encoding='utf-8'):
        line = line.rstrip()
        if line == '---':
            yield ''.join(code)
            code = []
            continue
        m = re.fullmatch(r'(.*?) *### *((go|java|rust):)? *(.*)', line)
        code.append(m.group(1) if m else line)
        code.append('\n')
    yield ''.join(code)


def split(out):
    os.makedirs(out + '/chunks', exist_ok=True)
    os.makedirs(out + '/py', exist_ok=True)
    for path in sorted(glob.glob(os.environ['SUITE'] + '/*/*.star')):
        impl = os.path.basename(os.path.dirname(path))
        for n, src in enumerate(chunks(path)):
            name = f'{impl}-{os.path.basename(path)[:-5]}-{n:03d}'
            open(f'{out}/chunks/{name}.star', 'w', encoding='utf-8').write(src)
            open(f'{out}/py/{name}.txt', 'w', encoding='utf-8').write(dump(src) + '\n')


def floats(text):
    return re.sub(r'\(float ([^ )]+)', lambda m: f'(float {float(m.group(1))!r}', text)


def compare(out):
    same = differ = 0
    for path in sorted(glob.glob(out + '/py/*.txt')):
        name = os.path.basename(path)
        py = open(path, encoding='utf-8').read().strip()
        java = floats(open(f'{out}/java/{name}', encoding='utf-8').read().strip())
        py_bad = py.startswith('ERROR') or '(unsupported' in py
        java_bad = java.startswith('ERROR')
        if py == java or (py_bad and java_bad):
            same += 1
            if py_bad:
                print(f'{name}: both reject\n    python: {py.splitlines()[0][:150]}\n    java:   {java[:150]}')
            continue
        differ += 1
        print(f'{name}: DIFFER\n    python: {py[:300]}\n    java:   {java[:300]}')
    print(f'TOTAL {same} agree, {differ} differ')


if __name__ == '__main__':
    if sys.argv[1] == '--compare':
        compare(sys.argv[2])
    else:
        split(sys.argv[1])
