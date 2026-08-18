package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the four internal operators of the {@code progv} lowering
 * ({@code LispMacroExpander.expandProgvForCompile}). Each arm of the lowering's
 * name-dispatch chain names its special LITERALLY, so these emit exactly the
 * thread-scoped save/set the {@code let} path emits for that one special
 * ({@code JvmLetCompiler}) -- with the previous-binding cell flowing as a VALUE (consed
 * into the lowering's save list) instead of into a save slot, because the bind and its
 * restore sit in different loop iterations of the same {@code unwind-protect}.
 *
 * <ul>
 * <li>{@code (%progv-dyn-bind NAME value)} -- {@code _dbind} a fresh cell over the
 * special's {@code _d$} ThreadLocal, answering the previous cell (possibly {@code null} =
 * no binding on this thread).
 * <li>{@code (%progv-dyn-unbind NAME prev)} -- {@code ThreadLocal.set(prev)}, the same
 * restore spelling every other exit path uses.
 * <li>{@code (%progv-genv)} / {@code (%progv-genv-set x)} -- read/write the eval
 * runtime's global env mirror {@code _genv}, whose binding nodes are ordinary cons cells
 * ({@code Object[]&#123;car, cdr&#125;}), so the lowering maintains the mirror in plain
 * Lisp. Only emitted when the eval runtime exists ({@code Ctx.evalStoreRef != null}).
 * </ul>
 */
final class JvmProgvCompiler {

	private JvmProgvCompiler() {
	}

	/** {@code (%progv-dyn-bind NAME value)}: push a binding, answer the previous cell. */
	static void compileDynBind(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
		ConstantPool.FieldrefConstant tl = dyn == null ? null : dyn.fields().get(name);
		if (dyn == null || tl == null) {
			// The lowering only generates arms for the program's specials, and a
			// progv-using program forces every special into the dynamically-bound set
			// (SpecialVarCollector.collectDynamicallyBound); a miss here must fail the
			// compile loudly, never fall back to a process-global binding.
			throw new IllegalStateException(
					"special variable " + name + " has no thread-local store for the progv lowering"
							+ " (SpecialVarCollector.collectDynamicallyBound missed the progv)");
		}
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(tl.index());
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(dyn.dbind().index());
	}

	/** {@code (%progv-dyn-unbind NAME prev)}: restore the saved cell; answers nil. */
	static void compileDynUnbind(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
		ConstantPool.FieldrefConstant tl = dyn == null ? null : dyn.fields().get(name);
		if (dyn == null || tl == null) {
			throw new IllegalStateException(
					"special variable " + name + " has no thread-local store for the progv lowering"
							+ " (SpecialVarCollector.collectDynamicallyBound missed the progv)");
		}
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(tl.index());
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(dyn.tlSet().index());
		ctx.emit(Opcode.ACONST_NULL);
	}

	/** {@code (%progv-genv)}: the eval runtime's global env mirror, as a Lisp alist. */
	static void compileGenvRead(JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(genvField(ctx, className).index());
	}

	/** {@code (%progv-genv-set x)}: replace the mirror alist; answers nil. */
	static void compileGenvWrite(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(genvField(ctx, className).index());
		ctx.emit(Opcode.ACONST_NULL);
	}

	private static ConstantPool.FieldrefConstant genvField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addFieldref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_genv"), ctx.cp.addUtf8("Ljava/lang/Object;")));
	}

}
