package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the three mutex primitives for WASM, where they are no-ops: both WASM backends
 * run a single instance on a single thread, so there is no second thread to exclude.
 * {@code make-mutex} yields the i31 constant 0 -- the handle is opaque on every backend
 * (an integer index on the interpreter, the {@code ReentrantLock} itself on the JVM), so
 * nothing portable may print or compare one -- and acquire/release are the identity on
 * their argument, matching what they return elsewhere.
 *
 * <p>
 * They must EXIST rather than be rejected: an undefined function is a COMPILE-time error
 * on the compile backends, so a library taking a lock on a path a WASM program never runs
 * would otherwise fail to build. This is the same reason cl-postgres' SCRAM names had to
 * land before the driver could be compiled at all.
 */
final class WasmMutexCompiler {

	private WasmMutexCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int arity = LispNames.MAKE_MUTEX.equals(member) ? 0 : 1;
		if (args.size() != arity + 1) {
			throw new UnsupportedOperationException(
					"rontolisp:" + member + " expects " + arity + " argument(s), got " + (args.size() - 1));
		}
		if (arity == 1) {
			// The identity: the argument's own value is the result, and evaluating it is
			// the only observable effect acquire/release have here.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			return;
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

}
