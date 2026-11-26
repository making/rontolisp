package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.FieldRefEntry;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal operators of the {@code progv} lowering
 * ({@code LispMacroExpander.expandProgvForCompile}). Each arm of the lowering's
 * name-dispatch chain names its special LITERALLY, so these emit exactly the save/set the
 * {@code let} path emits for that one special ({@code JvmLetCompiler}: shallow over its
 * {@code _g$} field, or thread-scoped over its {@code _d$} ThreadLocal) -- with the
 * previous state flowing as a VALUE (consed into the lowering's save list) instead of
 * into a save slot, because the bind and its restore sit in different loop iterations of
 * the same {@code unwind-protect}.
 *
 * <ul>
 * <li>{@code (%progv-dyn-bind NAME value)} -- set the special's {@code _g$} field,
 * answering its previous value; thread-scoped, {@code _dbind} a fresh cell over its
 * {@code _d$} ThreadLocal, answering the previous cell (possibly {@code null} = no
 * binding on this thread).
 * <li>{@code (%progv-dyn-unbind NAME prev)} -- put the previous state back, the same
 * restore every other exit path emits.
 * <li>{@code (%progv-unbound)} -- the UNBOUND marker, what a symbol past the end of the
 * values is bound to.
 * <li>{@code (%progv-genv)} / {@code (%progv-genv-set x)} -- read/write the eval
 * runtime's global env mirror {@code _genv}, whose binding nodes are ordinary cons cells
 * ({@code Object[]&#123;car, cdr&#125;}), so the lowering maintains the mirror in plain
 * Lisp. Only emitted when the eval runtime exists ({@code Ctx.evalStoreRef != null}).
 * </ul>
 */
final class JvmProgvCompiler {

	private JvmProgvCompiler() {
	}

	/**
	 * {@code (%progv-dyn-bind NAME value)}: push a binding, answer the previous state.
	 */
	static void compileDynBind(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		// The lowering only generates arms for the program's specials, and a progv-using
		// program forces every special into the dynamically-bound set
		// (SpecialVarCollector.collectDynamicallyBound), so a home is always there.
		FieldRefEntry home = JvmLetCompiler.bindingHome(name, ctx);
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		JvmDynVarRuntimeBuilder.UnboundMarker marker = ctx.unboundMarker;
		if (marker != null && !marker.globals().contains(name)) {
			// No read of this special tests for the marker (a cl symbol): it is bound to
			// nil where the progv had no value for it.
			MethodCode.Label valued = ctx.body.newLabel();
			ctx.body.dup();
			ctx.body.getstatic(marker.field());
			ctx.body.if_acmpne(valued);
			ctx.body.pop();
			ctx.body.aconst_null();
			ctx.body.labelBinding(valued);
		}
		ctx.body.getstatic(home).swap();
		if (ctx.threadScopedSpecials) {
			ctx.body.invokestatic(Objects.requireNonNull(ctx.dynVars).dbind());
		}
		else {
			ctx.body.putstatic(home);
		}
	}

	/** {@code (%progv-dyn-unbind NAME prev)}: restore the saved state; answers nil. */
	static void compileDynUnbind(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		FieldRefEntry home = JvmLetCompiler.bindingHome(name, ctx);
		if (ctx.threadScopedSpecials) {
			ctx.body.getstatic(home);
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			ctx.body.invokevirtual(Objects.requireNonNull(ctx.dynVars).tlSet());
		}
		else {
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			ctx.body.putstatic(home);
		}
		ctx.body.aconst_null();
	}

	/**
	 * {@code (%progv-unbound)}: the UNBOUND marker a symbol past the end of the values is
	 * bound to, nil in a program without one (no special's read tests for it).
	 */
	static void compileUnbound(JvmLispCompiler.Ctx ctx) {
		JvmDynVarRuntimeBuilder.UnboundMarker marker = ctx.unboundMarker;
		if (marker == null) {
			ctx.body.aconst_null();
		}
		else {
			ctx.body.getstatic(marker.field());
		}
	}

	/** {@code (%progv-genv)}: the eval runtime's global env mirror, as a Lisp alist. */
	static void compileGenvRead(JvmLispCompiler.Ctx ctx, String className) {
		ctx.body.getstatic(genvField(ctx, className));
	}

	/** {@code (%progv-genv-set x)}: replace the mirror alist; answers nil. */
	static void compileGenvWrite(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		ctx.body.putstatic(genvField(ctx, className)).aconst_null();
	}

	private static FieldRefEntry genvField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.fieldRef(ctx.cp.classEntry(className), "_genv", "Ljava/lang/Object;");
	}

}
