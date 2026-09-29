"""phase1.py apply|revert [FILE...]: the TEMPORARY patch a87 compared bytes under, kept as the
example of the technique. It reproduces what the raw lists emitted where the move deliberately
changes it, so the class files compare byte for byte; reverted, the two changes are measured
alone. Never committed applied. ROOT is the repository (default: the current directory).

- a spread dispatch case is measured the way the raw list did (its `aload 1` sites were the
  one-byte aload_1), so the dispatch partition is the old one;
- _cmul keeps the dead, never-patched `goto` (offset 0) the raw list left after its exact
  arm's areturn."""
import os
import sys

ROOT = os.environ.get("ROOT", os.getcwd()) + '/src/main/java/am/ik/rontolisp/codegen/jvm/'
FILES = {
    'JvmRuntimeBuilder.java': [
        ('''			int cost = cases.get(i).body().size() + DISPATCH_CASE_OVERHEAD;''',
         '''			int cost = cases.get(i).body().size() - cases.get(i).shortLoads() + DISPATCH_CASE_OVERHEAD;'''),
        ('''	private record Case(int funcId, MethodCode body) {
	}''', '''	private record Case(int funcId, MethodCode body, int shortLoads) {

		Case(int funcId, MethodCode body) {
			this(funcId, body, 0);
		}

	}'''),
        ('''		if (arityReporting.chkRef() != null) {
			code.aload(1);''', '''		int shortLoads = 0;
		if (arityReporting.chkRef() != null) {
			shortLoads++;
			code.aload(1);'''),
        ('''		for (int i = 0; i < positional; i++) {
			code.aload(1);
			for (int step = 0; step < i; step++) {''', '''		for (int i = 0; i < positional; i++) {
			shortLoads++;
			code.aload(1);
			for (int step = 0; step < i; step++) {'''),
        ('''		if (fi.variadic()) {
			code.aload(1);
			for (int step = 0; step < positional; step++) {''', '''		if (fi.variadic()) {
			shortLoads++;
			code.aload(1);
			for (int step = 0; step < positional; step++) {'''),
        ('''		code.invokestatic(fi.methodref().entry());
		code.areturn();
		return new Case(fi.funcId(), code);
	}

	// Replaces the cons on the stack''', '''		code.invokestatic(fi.methodref().entry());
		code.areturn();
		return new Case(fi.funcId(), code, shortLoads);
	}

	// Replaces the cons on the stack'''),
    ],
    'JvmComplexRuntimeBuilder.java': [
        ('''		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		c.labelBinding(toFloat);
		emitToDouble(c, refs, 2);
		c.dstore(8);''', '''		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		MethodCode.Label deadGoto = c.newBoundLabel();
		c.goto_(deadGoto);
		c.labelBinding(toFloat);
		emitToDouble(c, refs, 2);
		c.dstore(8);'''),
    ],
}
for name, edits in FILES.items():
    if len(sys.argv) > 2 and name not in sys.argv[2:]:
        continue
    p = ROOT + name
    s = open(p).read()
    for old, new in edits:
        a, b = (old, new) if sys.argv[1] == 'apply' else (new, old)
        assert s.count(a) == 1, (sys.argv[1], name, a[:80], s.count(a))
        s = s.replace(a, b)
    open(p, 'w').write(s)
print(sys.argv[1], "ok")
