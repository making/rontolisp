#!/usr/bin/env python3
"""Which functions of a Java program's IR have to be resumable for a thread to be
suspended and resumed, once a `synchronized` region that no suspension can be reached
from is known never to be held by a suspended thread.

    rontolisp Program.java --dump-ir > program.ir
    python3 held.py program.ir [...]

A thread is suspended in a wait, a sleep, a yield -- and in the entry of a monitor a
suspended thread may hold. A region is HELD ACROSS A SUSPENSION when its body reaches
one that does not give the region's own monitor up: a wait on its own lock gives it up,
any other suspension does not. An entry MAY BLOCK when the object it locks may be the
lock of a held region. Both are computed to a fixed point over the call graph.

Three readings of a call through a function value, printed side by side:
  none   -- not followed (what j83's reach.py counted),
  typed  -- a FUNCALL reaches the lambdas of the interface the called parameter's
            signature names (a lambda's interface is the parameter it is written as an
            argument of; one the IR does not tell reaches, and is reached by, any of
            its arity; UnaryOperator and BinaryOperator are read as the interfaces
            they extend, any other interface extending a functional one is not),
  flow   -- typed, and a FUNCALL of a parameter reaches the lambdas written as that
            argument where the function is called, through the parameters handing it on;
            a lambda that is stored, returned or bound, and a FUNCALL of anything but
            such a parameter, are read as in typed,
  arity  -- a FUNCALL reaches every lambda and every #'function of its arity.
"""
import re
import sys

sys.setrecursionlimit(100000)

TOKEN = re.compile(r'''\s*(?:
    (?P<open>\() | (?P<close>\)) |
    (?P<fn>\#') | (?P<quote>') | (?P<vec>\#\() |
    (?P<str>"(?:[^"\\]|\\.)*") |
    (?P<char>\#\\(?:[A-Za-z0-9_+-]+|.)) |
    (?P<atom>(?:\|[^|]*\||[^\s()'"|])+)
)''', re.X | re.S)


def read(text):
    """The forms of one line of an IR dump as nested lists; atoms are strings."""
    pos = 0
    stack = [[]]
    wrap = []
    n = len(text)
    while pos < n:
        m = TOKEN.match(text, pos)
        if not m:
            if text[pos:].strip() == '':
                break
            raise ValueError('cannot read at %d: %r' % (pos, text[pos:pos + 40]))
        pos = m.end()
        kind = m.lastgroup
        if kind == 'open' or kind == 'vec':
            stack.append([])
            wrap.append(None)
            continue
        if kind == 'fn' or kind == 'quote':
            wrap.append('FUNCTION' if kind == 'fn' else 'QUOTE')
            continue
        if kind == 'close':
            value = stack.pop()
            wrap.pop()
        elif kind == 'str':
            value = ('str', m.group('str'))
        else:
            value = m.group(kind)
        while wrap and wrap[-1] is not None:
            value = [wrap.pop(), value]
        stack[-1].append(value)
    return stack[0]


def is_sym(x):
    return isinstance(x, str)


def head(x):
    return x[0] if isinstance(x, list) and x and is_sym(x[0]) else None


def bare(name):
    """A symbol without its package: RONTOLISP::%JAVA-NPE -> %JAVA-NPE."""
    if name.startswith('|'):
        return name
    return name.split(':')[-1]


def unwrap(x):
    """The value under the null test the lowering puts around a receiver."""
    while True:
        if head(x) == 'LET' and len(x) == 3 and len(x[1]) == 1 and head(x[1][0]) == '|__cond|' \
                and head(x[2]) == 'IF' and x[2][1] == '|__cond|':
            x = x[1][0][1]
            continue
        return x


def lambda_of(x):
    """The lambda an argument is, through the bindings a method reference is made under."""
    while isinstance(x, list) and x:
        if head(x) == 'LAMBDA':
            return x
        if head(x) in ('LET', 'LET*', 'PROGN') and len(x) >= 2:
            x = x[-1]
            continue
        if head(x) == 'FUNCTION' and head(x[1]) == 'LAMBDA':
            return x[1]
        return None
    return None


SIG = re.compile(r'^\|(?P<owner>.*)\.(?P<name>[^.(]+)\((?P<params>[^)]*)\)(?P<rest>[^|]*)\|$')
SAME = {'java.util.function.UnaryOperator': 'java.util.function.Function',
        'java.util.function.BinaryOperator': 'java.util.function.BiFunction'}


class Fn:
    def __init__(self, name, params, body, java):
        self.name = name
        self.params = params
        self.body = body
        self.java = java  # written in Java: a method, or a lambda one makes
        self.named = java
        self.iface = None  # a lambda's interface when the IR tells it
        self.types = {}  # parameter -> the type its signature names
        self.owner = None
        self.general = False  # reaches a suspension that keeps every monitor of the caller
        self.waits = set()  # parameters whose monitor alone a suspension gives up
        self.why = None
        self.maker = None
        self.position = None  # a lambda: the (function, parameter) it is written as an argument of
        self.entry = False  # a lambda the run time starts a thread on
        self.sites = []

    def suspends(self):
        return self.general or bool(self.waits)


class Region:
    def __init__(self, fn, lock, kind):
        self.fn = fn
        self.lock = lock
        self.kind = kind  # ('static', C) ('class', C) ('object',) ('any',)
        self.held = False
        self.blocks = False


SEEDS_GENERAL = {'%JAVA-THREAD-SLEEP', '%JAVA-THREAD-YIELD'}
SEED_WAIT = '%JAVA-MONITOR-WAIT'
ENTER = '%JAVA-MONITOR-ENTER'
EXIT = '%JAVA-MONITOR-EXIT'
# the scheduler itself and the host's own parking: not bodies a program's thread is suspended in
OPAQUE = {'%JAVA-THREAD-SLEEP', '%JAVA-THREAD-YIELD', '%JAVA-MONITOR-WAIT', '%JAVA-MONITOR-ENTER',
          '%JAVA-MONITOR-EXIT', '%JAVA-THREAD-START', '%JAVA-THREAD-FINISH', '%JAVA-THREAD-MAIN',
          '%JAVA-PARK', '%JAVA-UNPARK', '%JAVA-THREAD-WAIT', '%JAVA-THREAD-NEXT', '%JAVA-THREAD-RUN',
          '%JAVA-MONITOR-WAITING', '%JAVA-INIT-BUSY', '%JAVA-AMONG-THREADS'}


class Program:
    def __init__(self, path, follow):
        self.follow = follow
        self.fns = {}
        self.lambdas = []
        self.parents = {}  # structure -> the one it includes
        self.accessors = set()
        self.setq_count = {}
        self.global_made = {}  # global -> the kinds of what it is set to
        self.regions = []
        self.referenced = {}  # arity -> named functions taken as values
        self.sources = {}  # (function, parameter) -> what its callers hand it
        self.escaping = set()  # (function, parameter) used as more than a callee
        self.reaching = {}
        tops = read(open(path).read())
        for form in tops:
            h = head(form)
            if h and bare(h) == 'DEFUN':
                name = form[1]
                if not is_sym(name):
                    continue
                params = [p for p in form[2] if is_sym(p) and not p.startswith('&')] if isinstance(form[2], list) else []
                key = name if name.startswith('|') else bare(name)
                fn = Fn(key, params, form[3:], name.startswith('|'))
                self.sign(fn)
                self.fns[key] = fn
            elif h and bare(h) == 'DEFSTRUCT':
                self.struct(form)
        self.top = Fn('<top>', [], [f for f in tops if not (head(f) and bare(head(f)) in ('DEFUN', 'DEFSTRUCT'))], False)
        for fn in list(self.fns.values()) + [self.top]:
            self.scan(fn, fn.body, [], fn)
        self.escapes()
        changed = True
        while changed:
            changed = False
            for fn in list(self.fns.values()) + self.lambdas + [self.top]:
                if fn.name in OPAQUE:
                    continue
                for site in fn.sites:
                    if site(fn):
                        changed = True

    def sign(self, fn):
        m = SIG.match(fn.name)
        if not m:
            return
        fn.owner = m.group('owner')
        types = [t for t in m.group('params').split(',') if t]
        offset = len(fn.params) - len(types)
        for i, t in enumerate(types):
            if 0 <= i + offset < len(fn.params):
                fn.types[fn.params[i + offset]] = SAME.get(t, t)

    def struct(self, form):
        spec = form[1]
        name = spec[0] if isinstance(spec, list) else spec
        conc = None
        if isinstance(spec, list):
            for option in spec[1:]:
                if head(option) == ':INCLUDE':
                    self.parents[name] = option[1]
                if head(option) == ':CONC-NAME':
                    conc = option[1]
        for slot in form[2:]:
            slot_name = slot[0] if isinstance(slot, list) else slot
            if is_sym(slot_name) and conc:
                self.accessors.add('|' + conc.strip('|') + slot_name.strip('|') + '|')

    def related(self, a, b):
        def up(c):
            seen = [c]
            while c in self.parents:
                c = self.parents[c]
                seen.append(c)
            return seen
        return a in up(b) or b in up(a)

    # -- one walk of a body: what it calls, under which regions ----------------------

    def scan(self, fn, forms, regions, owner):
        for form in forms:
            self.walk(fn, form, regions)

    def walk(self, fn, x, regions):
        if not isinstance(x, list) or not x:
            return
        h = head(x)
        if h is None:
            for sub in x:
                self.walk(fn, sub, regions)
            return
        b = bare(h)
        if b == 'QUOTE':
            return
        if b == 'FUNCTION':
            target = x[1]
            if head(target) == 'LAMBDA':
                self.make_lambda(fn, target, None)
            elif is_sym(target):
                self.reference(target)
            return
        if b == 'LAMBDA':
            self.make_lambda(fn, x, None)
            return
        if b == 'SETQ':
            for i in range(1, len(x) - 1, 2):
                self.setq_count[x[i]] = self.setq_count.get(x[i], 0) + 1
                self.global_made.setdefault(x[i], []).append(self.made_kind(x[i + 1]))
                self.walk(fn, x[i + 1], regions)
            return
        if b in ('LET', 'LET*') and len(x) >= 3 and isinstance(x[1], list):
            region = self.region_of(fn, x)
            if region:
                self.walk(fn, region.lock, regions)
                self.site_enter(fn, region, regions)
                protected = x[2][1]
                self.walk(fn, protected, regions + [region])
                return
            for binding in x[1]:
                if isinstance(binding, list) and len(binding) > 1:
                    self.walk(fn, binding[1], regions)
            self.scan(fn, x[2:], regions, fn)
            return
        if b == 'FUNCALL' and len(x) >= 2:
            operand = unwrap(x[1])
            args = x[2:]
            for a in x[1:]:
                self.walk_arg(fn, a, None, regions)
            if head(operand) == 'FUNCTION':
                if is_sym(operand[1]):
                    self.site_call(fn, operand[1], args, regions)
                return
            self.site_funcall(fn, operand, args, regions)
            return
        if b == ENTER:
            # an entry that is no region of this shape: locked to the end of the function
            region = Region(fn, unwrap(x[1]), self.kind_of(fn, unwrap(x[1])))
            self.regions.append(region)
            self.site_enter(fn, region, regions)
            return
        callee = h if h.startswith('|') else b
        target = self.fns.get(callee)
        args = x[1:]
        for i, a in enumerate(args):
            self.walk_arg(fn, a, (target, i) if target else None, regions)
        if b in SEEDS_GENERAL or b == SEED_WAIT or target is not None:
            self.site_call(fn, callee, args, regions)

    def walk_arg(self, fn, a, position, regions):
        lam = lambda_of(a)
        if position is not None and position[1] < len(position[0].params):
            target, i = position
            handed = self.sources.setdefault((target, target.params[i]), [])
            value = unwrap(a)
            holder = self.holder(fn, value) if is_sym(value) else None
            if lam is not None:
                handed.append(lam)
            elif holder is not None and holder.named and self.setq_count.get(value, 0) == 0:
                handed.append((holder, value))
            elif not (is_sym(value) and bare(value) in ('NIL', 'T')) and not isinstance(value, tuple):
                handed.append(None)
        if lam is not None and position is not None:
            target, i = position
            iface = None
            if i < len(target.params):
                iface = target.types.get(target.params[i])
            # the bindings around a method reference are evaluated by the maker
            x = a
            while x is not lam:
                if head(x) in ('LET', 'LET*'):
                    for binding in x[1]:
                        if isinstance(binding, list) and len(binding) > 1:
                            self.walk(fn, binding[1], regions)
                    for sub in x[2:-1]:
                        self.walk(fn, sub, regions)
                elif head(x) == 'PROGN':
                    for sub in x[1:-1]:
                        self.walk(fn, sub, regions)
                x = x[-1]
            made = self.make_lambda(fn, lam, iface)
            if i < len(target.params):
                made.position = (target, target.params[i])
            made.entry = target.name in OPAQUE
            handed[handed.index(lam)] = made
            return
        self.walk(fn, a, regions)

    def holder(self, fn, name):
        """The function a variable is a parameter of, a lambda's maker included."""
        while fn is not None:
            if name in fn.params:
                return fn
            fn = fn.maker
        return None

    def escapes(self):
        """The parameters used as more than the operand of a FUNCALL or an argument handed on."""
        edges = {}
        for fn in self.fns.values():
            params = set(fn.params)

            def visit(x, fine):
                if is_sym(x):
                    if x in params and not fine:
                        self.escaping.add((fn, x))
                    return
                if not isinstance(x, list) or not x:
                    return
                h = head(x)
                b = bare(h) if h else None
                if b == 'QUOTE':
                    return
                if b == 'FUNCALL' and len(x) >= 2:
                    visit(unwrap(x[1]), True)
                    for a in x[2:]:
                        visit(a, False)
                    return
                if b in ('NULL', 'NOT', 'FUNCTIONP'):
                    for a in x[1:]:
                        visit(a, True)
                    return
                if b == 'IF' and len(x) >= 3:
                    visit(x[1], True)
                    for a in x[2:]:
                        visit(a, False)
                    return
                if b == 'LAMBDA':
                    for a in x[2:]:
                        visit(a, False)
                    return
                callee = None
                if h is not None:
                    callee = self.fns.get(h if h.startswith('|') else b)
                if callee is not None:
                    for i, a in enumerate(x[1:]):
                        value = unwrap(a)
                        if is_sym(value) and value in params and i < len(callee.params):
                            edges.setdefault((fn, value), set()).add((callee, callee.params[i]))
                        else:
                            visit(a, False)
                    return
                for a in (x[1:] if h is not None else x):
                    visit(a, False)
            for form in fn.body:
                visit(form, False)
        for names in self.referenced.values():
            for name in names:
                target = self.fns[name]
                for param in target.params:
                    self.sources.setdefault((target, param), []).append(None)
        moved = True
        while moved:
            moved = False
            for key, targets in edges.items():
                if key not in self.escaping and any(t in self.escaping for t in targets):
                    self.escaping.add(key)
                    moved = True

    def pooled(self, lam):
        """Whether a lambda may be the value of anything a FUNCALL reads."""
        if lam.entry:
            return False
        return lam.position is None or lam.position in self.escaping

    def reach(self, key):
        """The lambdas handed to a parameter, and whether something the IR does not tell is."""
        if key not in self.reaching:
            lambdas, unknown, seen, todo = set(), False, set(), [key]
            while todo:
                at = todo.pop()
                if at in seen:
                    continue
                seen.add(at)
                for handed in self.sources.get(at, []):
                    if handed is None:
                        unknown = True
                    elif isinstance(handed, Fn):
                        lambdas.add(handed)
                    else:
                        todo.append(handed)
            self.reaching[key] = (lambdas, unknown)
        return self.reaching[key]

    def make_lambda(self, maker, lam, iface):
        params = [p for p in lam[1] if is_sym(p) and not p.startswith('&')] if isinstance(lam[1], list) else []
        fn = Fn('<lambda in %s>' % maker.name, params, lam[2:], False)
        fn.iface = None if iface == 'Object' else iface
        fn.maker = maker
        fn.owner = maker.owner
        fn.java = maker.java
        fn.named = False
        fn.types = {p: t for p, t in maker.types.items() if p not in params}
        self.lambdas.append(fn)
        self.scan(fn, fn.body, [], fn)
        return fn

    def reference(self, name):
        key = name if name.startswith('|') else bare(name)
        target = self.fns.get(key)
        if target is not None:
            self.referenced.setdefault(len(target.params), set()).add(key)

    def made_kind(self, value):
        value = unwrap(value)
        if head(value) == 'MAKE-ARRAY':
            return ('object',)
        h = head(value)
        if h and h.startswith('|'):
            m = SIG.match(h)
            if m and m.group('name') == 'new':
                return ('class', '|' + m.group('owner') + '|')
        return ('any',)

    def region_of(self, fn, x):
        """(LET ((t (%JAVA-MONITOR-ENTER lock))) (UNWIND-PROTECT body (%JAVA-MONITOR-EXIT t)))"""
        if len(x) != 3 or len(x[1]) != 1 or not isinstance(x[1][0], list) or len(x[1][0]) != 2:
            return None
        init = x[1][0][1]
        if not (head(init) and bare(head(init)) == ENTER):
            return None
        if not (head(x[2]) and bare(head(x[2])) == 'UNWIND-PROTECT'):
            return None
        lock = unwrap(init[1])
        region = Region(fn, lock, self.kind_of(fn, lock))
        self.regions.append(region)
        return region

    def kind_of(self, fn, lock):
        if head(lock) == 'QUOTE':
            return ('static', lock[1])
        if lock == '|this|' and fn.owner:
            return ('class', '|' + fn.owner + '|')
        if is_sym(lock):
            return ('global', lock)
        return ('any',)

    def resolve(self, kind):
        if kind[0] == 'global':
            made = set(self.global_made.get(kind[1], [('any',)]))
            if len(made) == 1:
                return next(iter(made))
            return ('any',)
        return kind

    def may_alias(self, a, b):
        a = self.resolve(a)
        b = self.resolve(b)
        if a[0] == 'static' or b[0] == 'static':
            return a == b
        if a[0] == 'any' or b[0] == 'any':
            return True
        if a[0] == 'object' or b[0] == 'object':
            return a[0] == b[0]
        return self.related(a[1], b[1])

    # -- the sites, each answering whether it moved the fixed point -----------------

    def hold(self, regions, why, except_lock=None, body_fn=None):
        moved = False
        for region in regions:
            if except_lock is not None and self.same_lock(region, except_lock, body_fn):
                continue
            if not region.held:
                region.held = True
                region.why = why
                moved = True
        return moved

    def same_lock(self, region, arg, fn):
        lock = region.lock
        if lock != arg:
            return False
        if is_sym(lock):
            if lock in fn.params or lock == '|this|':
                return True
            return self.setq_count.get(lock, 0) <= 1
        if head(lock) in self.accessors and len(lock) == 2 and is_sym(lock[1]):
            return True
        return False

    def site_call(self, fn, callee, args, regions):
        def step(fn_now):
            b = callee if callee.startswith('|') else bare(callee)
            if b in SEEDS_GENERAL:
                general, waits = True, []
            elif b == SEED_WAIT:
                general, waits = False, [0]
            else:
                target = self.fns.get(b)
                if target is None:
                    return False
                general = target.general
                waits = [target.params.index(p) for p in target.waits]
            return self.suspended(fn_now, general, waits, args, regions, b)
        fn.sites.append(step)

    def suspended(self, fn, general, waits, args, regions, why):
        moved = False
        if general:
            if not fn.general:
                fn.general = True
                fn.why = fn.why or why
                moved = True
            moved |= self.hold(regions, why)
        for i in waits:
            arg = unwrap(args[i]) if i < len(args) else None
            moved |= self.hold(regions, why, arg, fn)
            if is_sym(arg) and arg in fn.params and self.setq_count.get(arg, 0) == 0:
                if arg not in fn.waits:
                    fn.waits.add(arg)
                    fn.why = fn.why or why
                    moved = True
            elif not fn.general:
                # the monitor given up is none of the callers' as far as this function tells
                fn.general = True
                fn.why = fn.why or why
                moved = True
        return moved

    def site_funcall(self, fn, operand, args, regions):
        if self.follow == 'none':
            return
        if is_sym(operand) and bare(operand) == '%JAVA-SHARED':
            return  # the run time's own function, never a program's
        iface = fn.types.get(operand) if is_sym(operand) else None
        if operand == '|%o|' and fn.name.endswith('%v|'):
            iface = fn.owner  # a dispatcher: the lambdas of its interface
        arity = len(args)
        holder = self.holder(fn, operand) if is_sym(operand) else None
        tracked = holder is not None and holder.named and self.setq_count.get(operand, 0) == 0

        def step(fn_now):
            moved = False
            handed, unknown = set(), True
            if self.follow == 'flow' and tracked:
                handed, unknown = self.reach((holder, operand))
            for lam in self.lambdas:
                if len(lam.params) != arity or not lam.suspends():
                    continue
                if lam not in handed:
                    if not unknown or (self.follow == 'flow' and not self.pooled(lam)):
                        continue
                    if self.follow != 'arity' and lam.iface and (iface != lam.iface if iface else not fn.java):
                        continue
                moved |= self.suspended(fn_now, True, [], args, regions, 'a call of ' + lam.name)
            if not unknown:
                return moved
            for name in self.referenced.get(arity, ()):
                target = self.fns[name]
                if target.suspends():
                    moved |= self.suspended(fn_now, True, [], args, regions, "a call of #'" + name)
            return moved
        fn.sites.append(step)

    def site_enter(self, fn, region, regions):
        def step(fn_now):
            if region.blocks:
                return False
            for other in self.regions:
                if other.held and self.may_alias(region.kind, other.kind):
                    region.blocks = True
                    self.suspended(fn_now, True, [], [], regions,
                                   'the entry of a monitor held in ' + other.fn.name)
                    return True
            return False
        fn.sites.append(step)

    def report(self):
        java = [f for f in self.fns.values() if f.named]
        resumable = [f for f in java if f.suspends()]
        held = [r for r in self.regions if r.held]
        blocks = [r for r in self.regions if r.blocks]
        lambdas = [f for f in self.lambdas if f.suspends()]
        return len(java), len(resumable), len(self.regions), len(held), len(blocks), len(lambdas), resumable, held


def old_counts(path):
    """j83's reach.py: a name anywhere in a body is a call."""
    text = open(path).read()
    tok = re.compile(r'\|[^|]*\||[^\s()\'#`,"]+')
    defs = {}
    for line in text.split('\n'):
        m = re.match(r'\((?:CL:)?DEFUN\s+(\|[^|]*\||\S+)', line)
        if m:
            defs[m.group(1)] = line
    names = set(defs)
    calls = {n: {t for t in tok.findall(b) if t in names and t != n} for n, b in defs.items()}

    def closure(seeds):
        can = set(seeds)
        moved = True
        while moved:
            moved = False
            for n, c in calls.items():
                if n not in can and c & can:
                    can.add(n)
                    moved = True
        return can
    waits = [n for n in names if re.search(r'%JAVA-(THREAD-SLEEP|THREAD-YIELD|MONITOR-WAIT)$', n)]
    enter = waits + [n for n in names if n.endswith('%JAVA-MONITOR-ENTER')]
    java = [n for n in names if n.startswith('|')]
    return len(java), len([n for n in closure(waits) if n in java]), len([n for n in closure(enter) if n in java])


def main(argv):
    verbose = '-v' in argv
    paths = [a for a in argv if not a.startswith('-')]
    print('| program | functions | reach a wait, a sleep, a yield | or a monitor\'s entry | resumable: none / flow / typed / arity | regions | held | entries that may block |')
    print('|---|---|---|---|---|---|---|---|')
    for path in paths:
        functions, waits, enters = old_counts(path)
        cells = []
        for follow in ('none', 'flow', 'typed', 'arity'):
            program = Program(path, follow)
            cells.append(program.report())
            if verbose:
                print('--', path, follow, file=sys.stderr)
                for f in sorted(cells[-1][6], key=lambda f: f.name):
                    print('   resumable', f.name, 'general' if f.general else 'waits ' + ','.join(sorted(f.waits)),
                          '<-', f.why, file=sys.stderr)
                for r in cells[-1][7]:
                    print('   held', r.fn.name, r.lock, '<-', r.why, file=sys.stderr)
        name = path.split('/')[-1].rsplit('.', 1)[0]
        print('| %s | %d | %d | %d | %s | %d | %s | %s |' % (
            name, functions, waits, enters, ' / '.join(str(c[1]) for c in cells), cells[0][2],
            ' / '.join(str(c[3]) for c in cells), ' / '.join(str(c[4]) for c in cells)))


if __name__ == '__main__':
    main(sys.argv[1:])
