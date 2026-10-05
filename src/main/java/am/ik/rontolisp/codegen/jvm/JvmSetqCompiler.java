package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.FieldRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.GlobalVarCollector;

/**
 * Compiles the {@code setq} special form.
 */
final class JvmSetqCompiler {

	private JvmSetqCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if ((parts.size() - 1) % 2 != 0) {
			throw new IllegalArgumentException("setq requires an even number of arguments");
		}
		if (parts.size() == 1) {
			// (setq) -> nil
			ctx.body.aconst_null();
			return;
		}
		int pairCount = (parts.size() - 1) / 2;
		for (int p = 0; p < pairCount; p++) {
			compilePair(((LispSymbol) parts.get(1 + 2 * p)).name(), parts.get(2 + 2 * p), ctx, className);
			if (p < pairCount - 1) {
				// Discard the intermediate value; only the last pair's value is the
				// result
				ctx.body.pop();
			}
		}
	}

	/**
	 * Compiles a {@code setq} whose value is DISCARDED, leaving NOTHING on the operand
	 * stack -- or answers false, having emitted nothing, when the ordinary emission plus
	 * a {@code pop} is what the form needs.
	 *
	 * <p>
	 * The shape this exists for is an assignment into an unboxed dual-representation
	 * local ({@code .kb/jvm-int-fusion.md}): the store itself leaves the stack empty, so
	 * {@link #compile}'s re-read of the value -- the {@code _ubRead} call every counted
	 * loop's counter step and every accumulator paid once an iteration -- is pure waste
	 * when the caller is only going to pop it.
	 * @param cons the {@code setq} form
	 * @param ctx the compilation context
	 * @param className the class being generated
	 * @return true when the assignment was compiled for effect
	 */
	static boolean compileForEffect(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (!cons.isProperList()) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		int pairCount = (parts.size() - 1) / 2;
		if (pairCount == 0 || (parts.size() - 1) % 2 != 0) {
			return false;
		}
		for (int p = 0; p < pairCount; p++) {
			if (!(parts.get(1 + 2 * p) instanceof LispSymbol target)
					|| (JvmIntFusionCompiler.resolveRaw(target.name(), ctx) == null
							&& !ctx.rawDoubleLocals.containsKey(target.name()))) {
				return false;
			}
		}
		for (int p = 0; p < pairCount; p++) {
			String name = ((LispSymbol) parts.get(1 + 2 * p)).name();
			Integer rawDoubleSlot = ctx.rawDoubleLocals.get(name);
			if (rawDoubleSlot != null) {
				// The statement-position shape every float loop accumulator steps
				// with: the value lands raw and NOTHING is re-read for the caller to
				// pop (.kb/jvm-double-arithmetic.md).
				compileRawDoubleValue(parts.get(2 + 2 * p), ctx, className);
				ctx.body.dstore(rawDoubleSlot);
				continue;
			}
			JvmIntFusionCompiler.compileRawStore(parts.get(2 + 2 * p), ctx, className,
					java.util.Objects.requireNonNull(JvmIntFusionCompiler.resolveRaw(name, ctx)));
		}
		return true;
	}

	/**
	 * Compiles a value destined for a raw {@code double} slot, leaving a raw
	 * {@code double} on the stack. A value the routing predicate claims (a double
	 * literal, a declared variable, arithmetic over either) computes raw; an integer
	 * LITERAL widens at compile time, exactly as the double path's literal operands
	 * always have. Everything else compiles boxed and lands through the strict cast: a
	 * true declaration makes the cast free, a false one is a deterministic
	 * {@code ClassCastException} at the store ({@code .kb/declarations-type-checks.md}).
	 */
	static void compileRawDoubleValue(LispVal valueExpr, JvmLispCompiler.Ctx ctx, String className) {
		if (valueExpr instanceof am.ik.rontolisp.LispDouble || valueExpr instanceof am.ik.rontolisp.LispInteger
				|| JvmLispCompiler.containsDouble(valueExpr, ctx)) {
			JvmArithCompiler.compileUnboxedOperand(valueExpr, ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(valueExpr, ctx, className);
		JvmEmitHelper.unboxDeclaredDouble(ctx);
	}

	private static void compilePair(String name, LispVal valueExpr, JvmLispCompiler.Ctx ctx, String className) {
		if (ctx.mvChannel == null && am.ik.rontolisp.LispNames.MV_SPILL.equals(name)) {
			// No spill global: nothing can read what an expansion publishes, so the
			// store is dropped and only the value remains (.kb/multiple-values.md).
			JvmExprCompiler.compileExpr(valueExpr, ctx, className);
			return;
		}
		// An unboxed dual representation (.kb/jvm-int-fusion.md) -- a let local, or a
		// promoted top-level global no lexical binding shadows here: the store funnels
		// through the fused raw-store path, and the setq's value is re-read boxed. A raw
		// LOCAL is never special, never captured, never in ctx.locals; a raw GLOBAL is
		// never dynamically bound, so neither reaches the dual-bound special store below
		// (and its eval mirror is off by construction -- JvmRawGlobals).
		// A declared-float local in a raw double slot (.kb/jvm-double-arithmetic.md):
		// the value lands raw and the setq's value is re-boxed from the slot.
		Integer rawDoubleSlot = ctx.rawDoubleLocals.get(name);
		if (rawDoubleSlot != null) {
			compileRawDoubleValue(valueExpr, ctx, className);
			ctx.body.dstore(rawDoubleSlot).dload(rawDoubleSlot);
			JvmEmitHelper.boxDouble(ctx);
			return;
		}
		JvmIntFusionCompiler.RawLocal rawLocal = JvmIntFusionCompiler.resolveRaw(name, ctx);
		if (rawLocal != null) {
			JvmIntFusionCompiler.compileRawStore(valueExpr, ctx, className, rawLocal);
			JvmIntFusionCompiler.emitRawLocalBoxedRead(rawLocal, ctx);
			return;
		}
		if (valueExpr instanceof LispCons lambda && lambda.car() instanceof LispSymbol head
				&& am.ik.rontolisp.LispNames.LAMBDA.equals(head.name())
				&& am.ik.rontolisp.macro.LispMacroExpander.isLabelsFunctionVariable(name)) {
			// A labels expansion's one assignment: inside the lambda the variable is the
			// lambda itself, so a tail call through it is a jump (JvmSelfTailCall).
			ctx.lambdaSelfVars.put(lambda, name);
		}
		JvmExprCompiler.compileExpr(valueExpr, ctx, className);
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			int tempSlot = ctx.allocTemp();
			ctx.body.astore(tempSlot).aload(slot).checkcast(ctx.objectArrayClass);
			ctx.body.iconst_0().aload(tempSlot).aastore().aload(tempSlot);
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			int tempSlot = ctx.allocTemp();
			ctx.body.astore(tempSlot).aload(ctx.closureEnvSlot);
			JvmEmitHelper.emitIntConst(ctx, 1 + captureIdx);
			ctx.body.aaload().checkcast(ctx.objectArrayClass).iconst_0().aload(tempSlot);
			ctx.body.aastore().aload(tempSlot);
		}
		else if (slot == null && ctx.globals.contains(name)) {
			// A top-level global variable (not shadowed by a lexical here): store into
			// its
			// dedicated static field. Works from any method body, so a defun/lambda can
			// assign a global. The eval mirror runs everywhere the eval runtime does
			// (an assignment nested in a lambda never reached it, so a runtime
			// boundp/symbol-value read a stale mirror).
			// A dynamically-bound special assigns this thread's active binding instead
			// when one exists (emitGlobalStore).
			emitGlobalStore(name, ctx);
			mirrorGlobal(name, ctx);
		}
		else {
			// A plain lexical local of this method body. NOT mirrored into the eval
			// runtime: CL's eval sees only the null lexical environment, so no eval'd
			// form can name a top-level let/loop/do variable -- nor the temporaries the
			// macro expanders generate (__loop_acc0, the while cursor, __nrev_*), which
			// are not symbols in any package at all.
			ctx.body.dup();
			if (slot == null) {
				slot = ctx.allocLocal(name);
			}
			ctx.body.astore(slot);
		}
		// A special that is dual-bound here (a lexical slot/capture established by a
		// special-named let, see JvmLetCompiler): the assignment must reach the DYNAMIC
		// binding too, so a called function reading the special sees it.
		if (ctx.specialVars.contains(name) && (ctx.locals.containsKey(name) || ctx.captures.containsKey(name))
				&& ctx.globalFields.containsKey(name)) {
			emitGlobalStore(name, ctx);
		}
	}

	/**
	 * Stores the value on the stack into the global variable, leaving the value there. A
	 * special that is dynamically bound somewhere in the program writes this thread's
	 * active binding when one exists ({@code _dset}) and only falls through to the
	 * {@code _g$} global default when none does -- the CL rule that {@code setq} of a
	 * special assigns the current dynamic binding. Every other global stays a plain
	 * {@code putstatic}.
	 */
	static void emitGlobalStore(String name, JvmLispCompiler.Ctx ctx) {
		if (ctx.mvChannel != null && am.ik.rontolisp.LispNames.MV_SPILL.equals(name)) {
			ctx.body.dup();
			ctx.mvChannel.emitStore(ctx);
			return;
		}
		JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
		java.lang.classfile.constantpool.FieldRefEntry tlField = dyn == null ? null : dyn.fields().get(name);
		FieldRefEntry globalFieldIndex = java.util.Objects.requireNonNull(ctx.globalFields.get(name));
		if (dyn == null || tlField == null) {
			ctx.body.dup().putstatic(globalFieldIndex);
			return;
		}
		// stack: v -> v v tl -> v tl v -> v wrote? ; when 0, fall through to the global.
		ctx.body.dup().getstatic(tlField).swap().invokestatic(dyn.dset());
		MethodCode.Label ifWrotePos = ctx.body.newLabel();
		ctx.body.ifne(ifWrotePos);
		ctx.body.dup().putstatic(globalFieldIndex);
		ctx.body.labelBinding(ifWrotePos);
	}

	/**
	 * Mirrors a global variable binding into the embedded {@code eval} runtime's global
	 * environment, so an eval'd expression can resolve a variable that compiled code
	 * defined via {@code setq}/{@code defvar} (the compiled value otherwise lives only in
	 * a {@code main()} local the interpreter cannot see). Runs wherever the eval runtime
	 * does -- a store inside a defun/lambda body mirrors too, so a runtime
	 * {@code boundp}/{@code symbol-value} sees what the body assigned. No-op unless
	 * {@link #mirrorsGlobal} holds. Expects the assigned value on the stack and leaves it
	 * there (the {@code _store} call returns it).
	 */
	static void mirrorGlobal(String name, JvmLispCompiler.Ctx ctx) {
		if (!mirrorsGlobal(name, ctx)) {
			return;
		}
		// stack: value -> _store(name, value, null) -> value
		JvmEmitHelper.compileStringLiteral(name, ctx);
		ctx.body.swap().aconst_null();
		ctx.body.invokestatic(java.util.Objects.requireNonNull(ctx.evalStoreRef));
	}

	/**
	 * Whether an assignment to {@code name} here is mirrored into the eval runtime's
	 * global environment. Only a name with a global backing store qualifies: a lexical --
	 * a top-level {@code let}/{@code loop}/{@code do} variable, or a macro-generated
	 * temporary -- is invisible to {@code eval}, which resolves against the null lexical
	 * environment, so mirroring one is not conservatism but wasted work (and
	 * {@code _store} is a linear walk of the global alist, paid on every iteration of a
	 * loop that assigns a global.
	 * @param name the assigned variable name
	 * @param ctx the context the assignment is being emitted into
	 * @return {@code true} when the mirror emits
	 */
	static boolean mirrorsGlobal(String name, JvmLispCompiler.Ctx ctx) {
		return ctx.evalStoreRef != null && ctx.globals.contains(name) && GlobalVarCollector.mirrorsIntoEval(name);
	}

}
