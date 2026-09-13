#!/usr/bin/env python3
# Spike rewrite of NoGcWasmCompiler.java: an i32 house integer behind
# -Drontolisp.spike.i32=true, and a relaxed pass-through behind
# -Drontolisp.spike.passthrough=true. UNSOUND by design (see the report).
import re, sys
path = sys.argv[1]
src = open(path).read().split('\n')
out = []
EXCL = (1816, 2263)  # helper-body builders keep their genuine i64
X_OPS = {'I64_EXTEND_S_I32', 'I64_EXTEND_U_I32', 'I64_REINTERPRET_F64'}
for i, line in enumerate(src, 1):
    if line.startswith('public final class NoGcWasmCompiler implements LispCompiler {'):
        out.append(line)
        out.append('''
	// ---- SPIKE (measurement only, unsound) ----
	static final boolean SPIKE_I32 = Boolean.getBoolean("rontolisp.spike.i32");
	static final boolean SPIKE_PT = Boolean.getBoolean("rontolisp.spike.passthrough");
	static int I64i(int op) {
		if (!SPIKE_I32) return op;
		return switch (op) {
			case Instruction.I64_CONST -> Instruction.I32_CONST;
			case Instruction.I64_EQZ -> Instruction.I32_EQZ;
			case Instruction.I64_EQ -> Instruction.I32_EQ;
			case Instruction.I64_NE -> Instruction.I32_NE;
			case Instruction.I64_LT_S -> Instruction.I32_LT_S;
			case Instruction.I64_LE_S -> Instruction.I32_LE_S;
			case Instruction.I64_GT_S -> Instruction.I32_GT_S;
			case Instruction.I64_GE_S -> Instruction.I32_GE_S;
			case Instruction.I64_GT_U -> Instruction.I32_GT_U;
			case Instruction.I64_ADD -> Instruction.I32_ADD;
			case Instruction.I64_SUB -> Instruction.I32_SUB;
			case Instruction.I64_MUL -> Instruction.I32_MUL;
			case Instruction.I64_DIV_S -> Instruction.I32_DIV_S;
			case Instruction.I64_REM_S -> Instruction.I32_REM_S;
			case Instruction.I64_AND -> Instruction.I32_AND;
			case Instruction.I64_OR -> Instruction.I32_OR;
			case Instruction.I64_XOR -> Instruction.I32_XOR;
			case Instruction.I64_SHL -> Instruction.I32_SHL;
			case Instruction.I64_SHR_S -> Instruction.I32_SHR_S;
			case Instruction.I64_TRUNC_S_F64 -> Instruction.I32_TRUNC_S_F64;
			case Instruction.I64_TRUNC_U_F64 -> Instruction.I32_TRUNC_U_F64;
			case Instruction.F64_CONVERT_S_I64 -> Instruction.F64_CONVERT_S_I32;
			case Instruction.F64_CONVERT_U_I64 -> Instruction.F64_CONVERT_U_I32;
			default -> throw new IllegalStateException("spike: unmapped i64 op " + op);
		};
	}
	static Object I64x(int op) {
		return SPIKE_I32 ? new byte[0] : Integer.valueOf(op);
	}
	// ---- END SPIKE ----''')
        continue
    if EXCL[0] <= i <= EXCL[1]:
        out.append(line); continue
    def repl(m):
        op = m.group(1)
        return ('I64x' if op in X_OPS else 'I64i') + '(Instruction.' + op + ')'
    line = re.sub(r'Instruction\.(I64_[A-Z0-9_]+)', repl, line)
    line = line.replace('Instruction.I32_WRAP_I64', 'I64x(Instruction.I32_WRAP_I64)')
    line = line.replace('Instruction.F64_CONVERT_S_I64', 'I64i(Instruction.F64_CONVERT_S_I64)')
    line = line.replace('Instruction.F64_CONVERT_U_I64', 'I64i(Instruction.F64_CONVERT_U_I64)')
    if i == 179:
        line = line.replace('Type.I64.code()', '(SPIKE_I32 ? Type.I32.code() : Type.I64.code())')
    if i == 2275:
        line = line.replace('Type.I64', '(SPIKE_I32 ? Type.I32 : Type.I64)')
    line = line.replace('write(Type.I64.code())', 'write(Ty.INT.valType())')
    if line.startswith('	private static int emitBoundaryRangeGuard('):
        out.append(line)
        # the signature continues on the next line; inject after the opening brace
        out.append('__INJECT_GUARD__')
        continue
    if line.startswith('	private static boolean isPassThroughExport('):
        out.append(line); out.append('__INJECT_PT__'); continue
    out.append(line)
text = '\n'.join(out)
# inject after the '{' that closes the signature line following the marker
text = re.sub(r'__INJECT_GUARD__\n([^\n]*\{)\n', r'\1\n\t\tif (SPIKE_I32) { return 0; }\n', text)
text = text.replace('__INJECT_PT__\n\t\tif (mem.used()) {', '\t\tif (mem.used() && !SPIKE_PT) {')
text = text.replace('''			boolean identity = (hostType == BoundaryType.S64 && internal == Ty.INT)
					|| (hostType == BoundaryType.FLOAT && internal == Ty.FLOAT);''',
'''			boolean identity = (hostType == BoundaryType.S64 && internal == Ty.INT)
					|| (hostType == BoundaryType.FLOAT && internal == Ty.FLOAT)
					|| (SPIKE_PT && hostType.isInteger() && hostType.bits() <= 32 && internal == Ty.INT);''')
text = text.replace('''		return (decl.returnType() == BoundaryType.S64 && ret == Ty.INT)
				|| (decl.returnType() == BoundaryType.FLOAT && ret == Ty.FLOAT);''',
'''		return (decl.returnType() == BoundaryType.S64 && ret == Ty.INT)
				|| (decl.returnType() == BoundaryType.FLOAT && ret == Ty.FLOAT)
				|| (SPIKE_PT && decl.returnType().isInteger() && decl.returnType().bits() <= 32 && ret == Ty.INT);''')
assert '__INJECT' not in text, 'marker left behind'
open(path, 'w').write(text)
print('rewritten', path)
