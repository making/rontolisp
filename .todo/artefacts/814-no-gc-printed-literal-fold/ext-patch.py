import os

# MEASUREMENT HACK for the "print sites" question -- NOT a design. A (princ <literal>)
# site writes the literal's region directly (no header read) and its occurrences count
# as region-only for the header-free classification. The value of a (princ <literal>)
# whose literal went header-free is then VOID (nil), which is WRONG when a caller
# consumes it; the benchmarks below never do. Reverted after measuring.
p=os.environ.get('SRC', 'src/main/java')+'/am/ik/rontolisp/codegen/wasm/NoGcWasmCompiler.java'
s=open(p).read()

old="""			case STRING -> {
				// A string argument is passthrough -- nothing allocated, no bracket.
				// print (prin1 semantics) frames it in quotes and escapes the embedded
				// " / \\ so the text reads back; princ writes it bare.
				int s = fn.allocLocal(Ty.STRING);"""
new="""			case STRING -> {
				// SPIKE HACK: princ of a literal writes the region as two constants.
				if (!print && arg instanceof LispString lit) {
					emitWriteLiteral(fn, lit.value());
					Integer header = fn.mem.literals().get(lit.value());
					if (header == null) {
						return Ty.VOID;
					}
					w.write(Instruction.I32_CONST).writeSignedLeb128(header);
					return Ty.STRING;
				}
				// A string argument is passthrough -- nothing allocated, no bracket.
				// print (prin1 semantics) frames it in quotes and escapes the embedded
				// " / \\ so the text reads back; princ writes it bare.
				int s = fn.allocLocal(Ty.STRING);"""
assert s.count(old)==1, s.count(old); s=s.replace(old,new)

# classification: count (princ <lit>) occurrences as region-only, and do not gate on the fold
old="""		if (this.foldedImports.isEmpty()) {
			return Set.of();
		}
		Map<String, Integer> total = new HashMap<>();"""
new="""		Map<String, Integer> total = new HashMap<>();"""
assert s.count(old)==1; s=s.replace(old,new)

old="""		Map<String, Integer> folded = new HashMap<>();
		for (String name : this.foldedImports) {"""
new="""		Map<String, Integer> folded = new HashMap<>();
		// SPIKE HACK: every (princ <literal>) occurrence is a region-only use.
		for (String name : reachable) {
			if (!importDecls.containsKey(name)) {
				countPrincLiterals(progn(Objects.requireNonNull(defuns.get(name)).body()), folded);
			}
		}
		for (String name : this.foldedImports) {"""
assert s.count(old)==1; s=s.replace(old,new)

old="""	private static void countLiterals(LispVal v, Map<String, Integer> out) {"""
new="""	private static void countPrincLiterals(LispVal v, Map<String, Integer> out) {
		if (v instanceof LispCons c) {
			if (c.car() instanceof LispSymbol head && LispNames.PRINC.equals(head.name())
					&& c.cdr() instanceof LispCons rest && rest.car() instanceof LispString lit
					&& rest.cdr() instanceof LispNil) {
				out.merge(lit.value(), 1, Integer::sum);
				return;
			}
			countPrincLiterals(c.car(), out);
			countPrincLiterals(c.cdr(), out);
		}
	}

	private static void countLiterals(LispVal v, Map<String, Integer> out) {"""
assert s.count(old)==1; s=s.replace(old,new)
open(p,'w').write(s)
print("ext patch ok")
