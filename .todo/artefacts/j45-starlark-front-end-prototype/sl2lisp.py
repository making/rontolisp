#!/usr/bin/env python3
"""Prototype: lower Starlark source to rontolisp core forms (Common Lisp text).

Parsing borrows Python's `ast` (Starlark is syntactically a Python subset); the
lowering is the part under test.
"""
import ast
import sys
from collections import Counter


class Sym(str):
    pass


class StaticError(Exception):
    pass


def emit(x):
    if isinstance(x, Sym):
        return str(x)
    if isinstance(x, str):
        return '"' + x.replace('\\', '\\\\').replace('"', '\\"') + '"'
    if isinstance(x, bool):
        return 't' if x else 'nil'
    if isinstance(x, int):
        return str(x)
    if isinstance(x, float):
        r = repr(x)
        if 'e' in r:
            m, e = r.split('e')
            if '.' not in m:
                m += '.0'
            return f'{m}d{int(e)}'
        return r + 'd0'
    if isinstance(x, list):
        return '(' + ' '.join(emit(e) for e in x) + ')'
    raise TypeError(repr(x))


NONE = Sym(':none')
T = Sym('t')
NIL = Sym('nil')

# builtin name -> calling shape ('plain': positional + CL keywords, 'rest': one list)
BUILTINS = {
    'len': 'plain', 'str': 'plain', 'repr': 'plain', 'int': 'plain', 'float': 'plain',
    'bool': 'plain', 'list': 'plain', 'tuple': 'plain', 'range': 'plain', 'sorted': 'plain',
    'reversed': 'plain', 'enumerate': 'plain', 'any': 'plain', 'all': 'plain',
    'type': 'plain', 'hash': 'plain', 'abs': 'plain',
    'assert_eq': 'plain', 'assert_ne': 'plain', 'assert_': 'plain',
    'print': 'rest', 'fail': 'rest', 'min': 'rest', 'max': 'rest', 'zip': 'rest',
    'dict': 'dict',
}
CONSTANTS = {'True': T, 'False': NIL, 'None': NONE}

BINOPS = {
    ast.Add: '%sl-add', ast.Sub: '%sl-sub', ast.Mult: '%sl-mul', ast.Div: '%sl-div',
    ast.FloorDiv: '%sl-floordiv', ast.Mod: '%sl-mod', ast.BitOr: '%sl-or',
    ast.BitAnd: '%sl-and', ast.BitXor: '%sl-xor', ast.LShift: '%sl-lshift',
    ast.RShift: '%sl-rshift',
}
CMPOPS = {
    ast.Eq: ('%sl-eq', False), ast.NotEq: ('%sl-eq', True), ast.Lt: ('%sl-lt', False),
    ast.LtE: ('%sl-le', False), ast.Gt: ('%sl-gt', False), ast.GtE: ('%sl-ge', False),
    ast.In: ('%sl-in', False), ast.NotIn: ('%sl-in', True),
}


def target_names(t, out):
    if isinstance(t, ast.Name):
        out[t.id] += 1
    elif isinstance(t, (ast.Tuple, ast.List)):
        for e in t.elts:
            target_names(e, out)


def assigned(stmts, out=None):
    """Names bound by these statements, not descending into nested functions."""
    out = Counter() if out is None else out
    for s in stmts:
        if isinstance(s, ast.Assign):
            for t in s.targets:
                target_names(t, out)
        elif isinstance(s, ast.AugAssign):
            target_names(s.target, out)
        elif isinstance(s, ast.For):
            target_names(s.target, out)
            assigned(s.body, out)
        elif isinstance(s, ast.If):
            assigned(s.body, out)
            assigned(s.orelse, out)
        elif isinstance(s, ast.FunctionDef):
            out[s.name] += 1
    return out


def has_return(stmts):
    for s in stmts:
        if isinstance(s, ast.Return):
            return True
        if isinstance(s, (ast.For, ast.If)) and (has_return(s.body) or has_return(s.orelse)):
            return True
    return False


class Scope:
    def __init__(self, parent, names):
        self.parent = parent
        self.names = set(names)

    def is_local(self, name):
        s = self
        while s is not None:
            if name in s.names:
                return True
            s = s.parent
        return False


class Lowering:
    def __init__(self, prefix=''):
        self.prefix = prefix
        self.counter = 0
        self.globals = Counter()
        self.direct = {}

    def gensym(self, base):
        self.counter += 1
        return Sym(f'%{base}{self.counter}')

    def mangle(self, name, is_global):
        base = name if any(c.islower() for c in name) else 's%' + name
        if is_global:
            base = self.prefix + base
        return Sym('|' + base + '|')

    # ---- module ----

    def module(self, source):
        tree = ast.parse(source)
        self.globals = assigned(tree.body)
        for s in tree.body:
            if isinstance(s, ast.FunctionDef) and self.globals[s.name] == 1:
                a = s.args
                if not (a.vararg or a.kwarg or a.kwonlyargs):
                    self.direct[s.name] = s
        forms = []
        for s in tree.body:
            if isinstance(s, ast.FunctionDef) and s.name in self.direct:
                params, body, _ = self.fn_parts(s.args, s.body, None, s.name)
                name = self.mangle(s.name, True)
                forms.append(('defun', [Sym('defun'), name, params, body]))
                forms.append(('stmt', [Sym('setq'), name,
                                       self.fn_value(s.args, s.name, None, Sym("#'" + name))]))
            else:
                forms.append(('stmt', self.stmt(s, None)))
        return forms

    # ---- functions ----

    def fn_parts(self, args, body, scope, name):
        params = [a.arg for a in args.posonlyargs + args.args]
        kwonly = [a.arg for a in args.kwonlyargs]
        extra = ([args.vararg.arg] if args.vararg else []) + ([args.kwarg.arg] if args.kwarg else [])
        allp = params + kwonly + extra
        if len(set(allp)) != len(allp):
            raise StaticError(f'duplicate parameter in {name}')
        is_expr = isinstance(body, ast.expr)
        local = set(allp) | (set() if is_expr else set(assigned(body)))
        inner = Scope(scope, local)
        lets = [[self.mangle(v, False), NONE] for v in sorted(local - set(allp))]
        if is_expr:
            forms = [self.expr(body, inner)]
        else:
            forms = [self.stmt(s, inner) for s in body] + [NONE]
        lisp_body = [Sym('block'), Sym('%fn'), [Sym('let'), lets] + forms]
        return [self.mangle(p, False) for p in allp], lisp_body, (params, kwonly)

    def fn_value(self, args, name, scope, impl):
        params = [a.arg for a in args.posonlyargs + args.args]
        kwonly = [a.arg for a in args.kwonlyargs]
        required = Sym(':%required')
        defaults = [required] * (len(params) - len(args.defaults)) + \
            [self.expr(d, scope) for d in args.defaults]
        defaults += [required if d is None else self.expr(d, scope) for d in args.kw_defaults]
        return [Sym('%sl-mk-fn'), name, [Sym('list')] + [p for p in params + kwonly],
                [Sym('list')] + defaults, len(params),
                T if args.vararg else NIL, T if args.kwarg else NIL, impl]

    def closure(self, args, body, scope, name):
        params, lisp_body, _ = self.fn_parts(args, body, scope, name)
        return self.fn_value(args, name, scope, [Sym('lambda'), params, lisp_body])

    # ---- statements ----

    def var(self, name, scope):
        return self.mangle(name, not (scope is not None and scope.is_local(name)))

    def block(self, stmts, scope):
        return [Sym('progn')] + [self.stmt(s, scope) for s in stmts]

    def stmt(self, s, scope):
        if isinstance(s, ast.Expr):
            return self.expr(s.value, scope)
        if isinstance(s, ast.Assign):
            if len(s.targets) != 1:
                raise StaticError('chained assignment')
            return self.assign(s.targets[0], self.expr(s.value, scope), scope)
        if isinstance(s, ast.AugAssign):
            op = '%sl-iadd' if isinstance(s.op, ast.Add) else BINOPS[type(s.op)]
            t = s.target
            if isinstance(t, ast.Name):
                v = self.var(t.id, scope)
                return [Sym('setq'), v, [Sym(op), v, self.expr(s.value, scope)]]
            if isinstance(t, ast.Subscript):
                o, k = self.gensym('o'), self.gensym('k')
                return [Sym('let'), [[o, self.expr(t.value, scope)], [k, self.expr(t.slice, scope)]],
                        [Sym('%sl-setindex'), o, k,
                         [Sym(op), [Sym('%sl-index'), o, k], self.expr(s.value, scope)]]]
            raise StaticError('bad augmented assignment target')
        if isinstance(s, ast.If):
            return [Sym('if'), self.test(s.test, scope), self.block(s.body, scope),
                    self.block(s.orelse, scope)]
        if isinstance(s, ast.For):
            if s.orelse:
                raise StaticError('for/else')
            body = [self.stmt(b, scope) for b in s.body]
            return self.for_loop(s.target, self.expr(s.iter, scope), body, scope, has_return(s.body))
        if isinstance(s, ast.Return):
            if scope is None:
                raise StaticError('return outside function')
            return [Sym('return-from'), Sym('%fn'), self.expr(s.value, scope) if s.value else NONE]
        if isinstance(s, ast.Break):
            return [Sym('return-from'), Sym('%brk'), NIL]
        if isinstance(s, ast.Continue):
            return [Sym('return-from'), Sym('%cont'), NIL]
        if isinstance(s, ast.Pass):
            return NIL
        if isinstance(s, ast.FunctionDef):
            return [Sym('setq'), self.var(s.name, scope), self.closure(s.args, s.body, scope, s.name)]
        raise StaticError(f'unsupported statement {type(s).__name__}')

    def assign(self, t, value, scope):
        if isinstance(t, ast.Name):
            return [Sym('setq'), self.var(t.id, scope), value]
        if isinstance(t, ast.Subscript):
            return [Sym('%sl-setindex'), self.expr(t.value, scope), self.expr(t.slice, scope), value]
        if isinstance(t, (ast.Tuple, ast.List)):
            tmp = self.gensym('u')
            return [Sym('let'), [[tmp, [Sym('%sl-unpack'), value, len(t.elts)]]]] + \
                [self.assign(e, [Sym('aref'), tmp, i], scope) for i, e in enumerate(t.elts)]
        raise StaticError(f'cannot assign to {type(t).__name__}')

    def for_loop(self, target, iterable, body, scope, returns):
        seq, it, n, i, top = (self.gensym(b) for b in ('seq', 'it', 'n', 'i', 'top'))
        loop = [Sym('block'), Sym('%brk'),
                [Sym('tagbody'), top,
                 [Sym('if'), [Sym('>='), i, n], [Sym('return-from'), Sym('%brk'), NIL]],
                 self.assign(target, [Sym('aref'), it, i], scope),
                 [Sym('setq'), i, [Sym('+'), i, 1]],
                 [Sym('block'), Sym('%cont')] + body,
                 [Sym('go'), top]]]
        end = [Sym('%sl-iter-end'), seq]
        tail = [[Sym('unwind-protect'), loop, end]] if returns else [loop, end]
        return [Sym('let*'), [[seq, iterable], [it, [Sym('%sl-iter-begin'), seq]],
                              [n, [Sym('length'), it]], [i, 0]]] + tail

    # ---- expressions ----

    def is_bool(self, e):
        if isinstance(e, ast.Compare):
            return True
        if isinstance(e, ast.UnaryOp) and isinstance(e.op, ast.Not):
            return True
        if isinstance(e, ast.Constant) and isinstance(e.value, bool):
            return True
        return False

    def test(self, e, scope):
        if isinstance(e, ast.BoolOp):
            op = 'and' if isinstance(e.op, ast.And) else 'or'
            return [Sym(op)] + [self.test(v, scope) for v in e.values]
        if self.is_bool(e):
            return self.expr(e, scope)
        return [Sym('%sl-truth'), self.expr(e, scope)]

    def expr(self, e, scope):
        if isinstance(e, ast.Constant):
            v = e.value
            if v is None:
                return NONE
            if v is True:
                return T
            if v is False:
                return NIL
            if isinstance(v, (int, float, str)):
                return v
            raise StaticError(f'unsupported literal {v!r}')
        if isinstance(e, ast.Name):
            if scope is not None and scope.is_local(e.id):
                return self.mangle(e.id, False)
            if e.id in self.globals:
                return self.mangle(e.id, True)
            if e.id in CONSTANTS:
                return CONSTANTS[e.id]
            if e.id in BUILTINS and BUILTINS[e.id] == 'plain':
                return Sym("#'%sl-b-" + e.id)
            raise StaticError(f'undefined: {e.id}')
        if isinstance(e, ast.BinOp):
            return [Sym(BINOPS[type(e.op)]), self.expr(e.left, scope), self.expr(e.right, scope)]
        if isinstance(e, ast.UnaryOp):
            if isinstance(e.op, ast.Not):
                return [Sym('not'), self.test(e.operand, scope)]
            op = {ast.USub: '%sl-neg', ast.UAdd: '%sl-pos', ast.Invert: '%sl-invert'}[type(e.op)]
            return [Sym(op), self.expr(e.operand, scope)]
        if isinstance(e, ast.Compare):
            if len(e.ops) != 1:
                raise StaticError('comparison chaining is not supported')
            fn, negate = CMPOPS[type(e.ops[0])]
            form = [Sym(fn), self.expr(e.left, scope), self.expr(e.comparators[0], scope)]
            return [Sym('not'), form] if negate else form
        if isinstance(e, ast.BoolOp):
            is_and = isinstance(e.op, ast.And)
            form = self.expr(e.values[-1], scope)
            for v in reversed(e.values[:-1]):
                tmp = self.gensym('v')
                arms = [form, tmp] if is_and else [tmp, form]
                form = [Sym('let'), [[tmp, self.expr(v, scope)]],
                        [Sym('if'), [Sym('%sl-truth'), tmp]] + arms]
            return form
        if isinstance(e, ast.IfExp):
            return [Sym('if'), self.test(e.test, scope), self.expr(e.body, scope),
                    self.expr(e.orelse, scope)]
        if isinstance(e, ast.List):
            return [Sym('%sl-list')] + [self.expr(x, scope) for x in e.elts]
        if isinstance(e, ast.Tuple):
            return [Sym('%sl-tuple')] + [self.expr(x, scope) for x in e.elts]
        if isinstance(e, ast.Dict):
            form = [Sym('%sl-dict')]
            for k, v in zip(e.keys, e.values):
                form += [self.expr(k, scope), self.expr(v, scope)]
            return form
        if isinstance(e, ast.Subscript):
            obj = self.expr(e.value, scope)
            if isinstance(e.slice, ast.Slice):
                sl = e.slice
                return [Sym('%sl-slice'), obj] + \
                    [self.expr(p, scope) if p is not None else NONE for p in (sl.lower, sl.upper, sl.step)]
            return [Sym('%sl-index'), obj, self.expr(e.slice, scope)]
        if isinstance(e, ast.Lambda):
            return self.closure(e.args, e.body, scope, 'lambda')
        if isinstance(e, (ast.ListComp, ast.DictComp)):
            return self.comprehension(e, scope)
        if isinstance(e, ast.Call):
            return self.call(e, scope)
        if isinstance(e, ast.Attribute):
            return [Sym('%sl-getattr'), self.expr(e.value, scope), e.attr]
        raise StaticError(f'unsupported expression {type(e).__name__}')

    def comprehension(self, e, scope):
        names = Counter()
        for g in e.generators:
            target_names(g.target, names)
        inner = Scope(scope, names)
        acc = self.gensym('acc')
        is_dict = isinstance(e, ast.DictComp)

        def gen(i):
            if i == len(e.generators):
                if is_dict:
                    return [Sym('%sl-dict-put'), acc, self.expr(e.key, inner), self.expr(e.value, inner)]
                return [Sym('%sl-acc-push'), acc, self.expr(e.elt, inner)]
            g = e.generators[i]
            body = gen(i + 1)
            for cond in reversed(g.ifs):
                body = [Sym('if'), self.test(cond, inner), body]
            return self.for_loop(g.target, self.expr(g.iter, scope if i == 0 else inner), [body],
                                 inner, False)

        lets = [[acc, [Sym('%sl-new-dict' if is_dict else '%sl-list')]]] + \
            [[self.mangle(v, False), NONE] for v in sorted(names)]
        return [Sym('let'), lets, gen(0), acc]

    def call(self, e, scope):
        pos = [a for a in e.args if not isinstance(a, ast.Starred)]
        star = [a.value for a in e.args if isinstance(a, ast.Starred)]
        kws = [k for k in e.keywords if k.arg is not None]
        dstar = [k.value for k in e.keywords if k.arg is None]
        simple = not star and not dstar
        f = e.func

        def pos_list():
            form = [Sym('list')] + [self.expr(a, scope) for a in pos]
            for s in star:
                form = [Sym('append'), form, [Sym('%sl-elems'), self.expr(s, scope)]]
            return form

        def kw_plist():
            form = [Sym('list')]
            for k in kws:
                form += [k.arg, self.expr(k.value, scope)]
            for d in dstar:
                form = [Sym('append'), form, [Sym('%sl-dict-plist'), self.expr(d, scope)]]
            return form

        def cl_keys():
            out = []
            for k in kws:
                out += [Sym(':' + k.arg), self.expr(k.value, scope)]
            return out

        if isinstance(f, ast.Attribute):
            obj = self.expr(f.value, scope)
            if f.attr == 'format':
                return [Sym('%sl-m-format'), obj, pos_list(), kw_plist()]
            if not simple:
                raise StaticError('star arguments to a method')
            return [Sym('%sl-m-' + f.attr), obj] + [self.expr(a, scope) for a in pos] + cl_keys()
        if isinstance(f, ast.Name):
            local = scope is not None and scope.is_local(f.id)
            if not local and f.id not in self.globals and f.id in BUILTINS:
                shape = BUILTINS[f.id]
                name = Sym('%sl-b-' + f.id)
                if shape == 'dict':
                    return [name, pos_list(), kw_plist()]
                if shape == 'rest' and not dstar:
                    return [name, pos_list()] + cl_keys()
                if simple:
                    return [name] + [self.expr(a, scope) for a in pos] + cl_keys()
                return [Sym('%sl-call'), Sym("#'" + name), pos_list(), kw_plist()]
            if not local and f.id in self.direct and simple and not kws:
                d = self.direct[f.id]
                if len(pos) == len(d.args.args) + len(d.args.posonlyargs):
                    return [self.mangle(f.id, True)] + [self.expr(a, scope) for a in pos]
        return [Sym('%sl-call'), self.expr(f, scope), pos_list(), kw_plist() if (kws or dstar) else NIL]


def translate(source, prefix=''):
    return Lowering(prefix).module(source)


if __name__ == '__main__':
    src = open(sys.argv[1], encoding='utf-8').read()
    for kind, form in translate(src):
        print(emit(form))
