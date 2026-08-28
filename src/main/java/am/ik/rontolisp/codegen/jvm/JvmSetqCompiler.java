package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

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
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		int pairCount = (parts.size() - 1) / 2;
		for (int p = 0; p < pairCount; p++) {
			compilePair(((LispSymbol) parts.get(1 + 2 * p)).name(), parts.get(2 + 2 * p), ctx, className);
			if (p < pairCount - 1) {
				// Discard the intermediate value; only the last pair's value is the
				// result
				ctx.emit(Opcode.POP);
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
					|| JvmIntFusionCompiler.resolveRaw(target.name(), ctx) == null) {
				return false;
			}
		}
		for (int p = 0; p < pairCount; p++) {
			String name = ((LispSymbol) parts.get(1 + 2 * p)).name();
			JvmIntFusionCompiler.compileRawStore(parts.get(2 + 2 * p), ctx, className,
					java.util.Objects.requireNonNull(JvmIntFusionCompiler.resolveRaw(name, ctx)));
		}
		return true;
	}

	private static void compilePair(String name, LispVal valueExpr, JvmLispCompiler.Ctx ctx, String className) {
		// An unboxed dual representation (.kb/jvm-int-fusion.md) -- a let local, or a
		// promoted top-level global no lexical binding shadows here: the store funnels
		// through the fused raw-store path, and the setq's value is re-read boxed. A raw
		// LOCAL is never special, never captured, never in ctx.locals; a raw GLOBAL is
		// never dynamically bound, so neither reaches the dual-bound special store below
		// (and its eval mirror is off by construction -- JvmRawGlobals).
		JvmIntFusionCompiler.RawLocal rawLocal = JvmIntFusionCompiler.resolveRaw(name, ctx);
		if (rawLocal != null) {
			JvmIntFusionCompiler.compileRawStore(valueExpr, ctx, className, rawLocal);
			JvmIntFusionCompiler.emitRawLocalBoxedRead(rawLocal, ctx);
			return;
		}
		JvmExprCompiler.compileExpr(valueExpr, ctx, className);
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			JvmEmitHelper.emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
		}
		else if (slot == null && ctx.globals.contains(name)) {
			// A top-level global variable (not shadowed by a lexical here): store into
			// its
			// dedicated static field. Works from any method body, so a defun/lambda can
			// assign a global. The eval mirror still runs at top level (no-op elsewhere).
			// A dynamically-bound special assigns this thread's active binding instead
			// when one exists (emitGlobalStore).
			emitGlobalStore(name, ctx);
			mirrorTopLevelGlobal(name, ctx);
		}
		else {
			// A plain lexical local of this method body. NOT mirrored into the eval
			// runtime: CL's eval sees only the null lexical environment, so no eval'd
			// form can name a top-level let/loop/do variable -- nor the temporaries the
			// macro expanders generate (__loop_acc0, the while cursor, __nrev_*), which
			// are not symbols in any package at all.
			ctx.emit(Opcode.DUP);
			if (slot == null) {
				slot = ctx.allocLocal(name);
			}
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
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
	private static void emitGlobalStore(String name, JvmLispCompiler.Ctx ctx) {
		JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
		am.ik.jvm.ConstantPool.FieldrefConstant tlField = dyn == null ? null : dyn.fields().get(name);
		int globalFieldIndex = java.util.Objects.requireNonNull(ctx.globalFields.get(name)).index();
		if (dyn == null || tlField == null) {
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.PUTSTATIC);
			ctx.emitU2(globalFieldIndex);
			return;
		}
		// stack: v -> v v tl -> v tl v -> v wrote? ; when 0, fall through to the global.
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(tlField.index());
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(dyn.dset().index());
		int ifWrotePos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(globalFieldIndex);
		JvmEmitHelper.patchBranch(ctx, ifWrotePos, ctx.code.size());
	}

	/**
	 * Mirrors a top-level global variable binding into the embedded {@code eval}
	 * runtime's global environment, so an eval'd expression can resolve a variable that
	 * compiled code defined via {@code setq}/{@code defvar} (the compiled value otherwise
	 * lives only in a {@code main()} local the interpreter cannot see). No-op unless
	 * {@link #mirrorsTopLevelGlobal} holds. Expects the assigned value on the stack and
	 * leaves it there (the {@code _store} call returns it).
	 */
	static void mirrorTopLevelGlobal(String name, JvmLispCompiler.Ctx ctx) {
		if (!mirrorsTopLevelGlobal(name, ctx)) {
			return;
		}
		// stack: value -> _store(name, value, null) -> value
		JvmEmitHelper.compileStringLiteral(name, ctx);
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(java.util.Objects.requireNonNull(ctx.evalStoreRef).index());
	}

	/**
	 * Whether an assignment to {@code name} here is mirrored into the eval runtime's
	 * global environment. Only a name with a global backing store qualifies: a lexical --
	 * a top-level {@code let}/{@code loop}/{@code do} variable, or a macro-generated
	 * temporary -- is invisible to {@code eval}, which resolves against the null lexical
	 * environment, so mirroring one is not conservatism but wasted work (and
	 * {@code _store} is a linear walk of the global alist, paid on every iteration of a
	 * top-level loop).
	 * @param name the assigned variable name
	 * @param ctx the context the assignment is being emitted into
	 * @return {@code true} when the mirror emits
	 */
	static boolean mirrorsTopLevelGlobal(String name, JvmLispCompiler.Ctx ctx) {
		return ctx.topLevel && ctx.evalStoreRef != null && ctx.globals.contains(name);
	}

}
