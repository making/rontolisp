package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.LetBoundDesignators;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code let} special form.
 *
 * <p>
 * A binding whose name is a special (dynamically bound) variable establishes a
 * THREAD-SCOPED dynamic binding over the special's {@code _d$} ThreadLocal (interpreter
 * parity: two http-handler request threads binding the same special must not clobber each
 * other): {@code _dbind} installs a fresh cell holding the init value and answers the
 * previous cell, saved in a temp and put back with {@code ThreadLocal.set} when the body
 * exits normally. The new value is visible (via the dynamic-first {@code _dget} read) to
 * any function called during the body on THIS thread; other threads keep reading the
 * {@code _g$} global default. A {@code return}/{@code return-from} that unwinds across
 * the {@code let} boundary restores through {@link JvmLispCompiler.Ctx#specialBindScopes}
 * (a plain WASM {@code return} through a trampoline and {@code go} across the binding
 * remain the known compile-path holes; the interpreter restores on every exit).
 */
final class JvmLetCompiler {

	private JvmLetCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, null);
	}

	/**
	 * @param tail the tail spine this {@code let} ends, or null. When it has one, the
	 * body forms JOIN the spine and the after-the-body work (the dynamic-binding
	 * restores, then the compile-time scope restore) becomes a {@code Cleanup} item on it
	 * -- the same emission in the same order, with a split point between any two body
	 * forms ({@link JvmBodyOutliner}).
	 */
	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, JvmBodyOutliner.@Nullable Tail tail) {
		// A binding that only holds a literal designator for the body's funcall sites is
		// propagated into them and dropped, so the designator never becomes a VALUE here
		// (LetBoundDesignators; the WASM twin does the same).
		LispCons letForm = LetBoundDesignators.propagate(cons, ctx.specialVars, ctx.functions.keySet());
		List<LispVal> parts = letForm.toList();
		// A bare symbol entry is an init-less binding to nil.
		LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		Set<String> savedBoxedVars = new HashSet<>(ctx.boxedVars);
		Map<String, JvmIntFusionCompiler.RawLocal> savedRawLocals = new HashMap<>(ctx.rawLocals);
		Map<String, JvmIntFusionCompiler.LocalIntLambda> savedLocalIntLambdas = new HashMap<>(ctx.localIntLambdas);
		int savedNextLocal = ctx.nextLocal;
		// Every binding name takes part in capture analysis: a special-named binding is
		// DUAL-BOUND (dynamic set + a lexical slot, mirroring the interpreter), so a
		// closure built in the body captures the entry value and can read it after the
		// dynamic extent ended (cl-ppcre's end-string).
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				letVarNames.add(((LispSymbol) ((LispCons) binding).toList().get(0)).name());
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(parts.subList(2, parts.size()), letVarNames,
				ctx.functions.keySet());
		ctx.boxedVars = new HashSet<>(ctx.boxedVars);
		// Each dynamic (special) binding established here: {tlFieldIndex, saveSlot}.
		// Restored (reverse order) after the body, before the scope is popped.
		List<int[]> dynamicRestores = null;
		Set<String> boundInThisLet = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (ctx.specialVars.contains(name)) {
					// DUAL-BIND (interpreter parity): the thread's dynamic binding is
					// pushed via _dbind (the binding a called function reads through
					// _dget), AND the same value gets a lexical slot so a closure built
					// in the body captures it -- the closure may run after this extent
					// ended and restored the previous binding (cl-ppcre's end-string).
					// Body reads resolve dynamic-first; a setq of the name writes BOTH
					// (JvmSetqCompiler).
					JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
					FieldrefConstant tlField = dyn == null ? null : dyn.fields().get(name);
					if (dyn == null || tlField == null) {
						// The pre-pass promised every dynamically-bound special a
						// ThreadLocal; a miss here must fail the compile loudly, never
						// fall back to a silently process-global binding.
						throw new IllegalStateException(
								"special variable " + name + " is dynamically bound here but has no thread-local store"
										+ " (SpecialVarCollector.collectDynamicallyBound missed this binding form)");
					}
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.GETSTATIC);
					ctx.emitU2(tlField.index());
					ctx.emit(Opcode.SWAP);
					ctx.emit(Opcode.INVOKESTATIC);
					ctx.emitU2(dyn.dbind().index());
					int saveSlot = ctx.allocTemp();
					ctx.emit(Opcode.ASTORE);
					ctx.emit(saveSlot);
					if (dynamicRestores == null) {
						dynamicRestores = new ArrayList<>();
					}
					dynamicRestores.add(new int[] { tlField.index(), saveSlot });
					ctx.specialBindScopes.push(new int[] { tlField.index(), saveSlot, ctx.blockTargets.size() });
					if (capturedInLet.contains(name)) {
						int tmpSlot = ctx.allocTemp();
						ctx.emit(Opcode.ASTORE);
						ctx.emit(tmpSlot);
						ctx.emit(Opcode.ICONST_1);
						ctx.emit(Opcode.ANEWARRAY);
						ctx.emitU2(ctx.objectClass.index());
						ctx.emit(Opcode.DUP);
						ctx.emit(Opcode.ICONST_0);
						ctx.emit(Opcode.ALOAD);
						ctx.emit(tmpSlot);
						ctx.emit(Opcode.AASTORE);
					}
					int lexSlot = ctx.allocLocal(name);
					ctx.emit(Opcode.ASTORE);
					ctx.emit(lexSlot);
					ctx.rawLocals.remove(name);
					ctx.localIntLambdas.remove(name);
					if (capturedInLet.contains(name)) {
						ctx.boxedVars.add(name);
					}
					else {
						ctx.boxedVars.remove(name);
					}
					continue;
				}
				// An unboxed dual-representation binding (.kb/jvm-int-fusion.md): a
				// plain lexical whose init or some body assignment is integer-shaped
				// gets a raw long slot plus a boxed shadow; registered AFTER the init
				// compiled, so the init still resolves an outer same-named binding.
				if (!capturedInLet.contains(name) && !boundInThisLet.contains(name) && ctx.nextLocal + 4 <= 250
						&& JvmIntFusionCompiler.rawBindingEligible(name, pairList.get(1),
								parts.subList(2, parts.size()), ctx)) {
					int longSlot = ctx.allocTemp();
					ctx.allocTemp();
					int shadowSlot = ctx.allocTemp();
					int flagSlot = ctx.allocTemp();
					JvmIntFusionCompiler.RawLocal rawLocal = new JvmIntFusionCompiler.RawLocal(longSlot, shadowSlot,
							flagSlot);
					// Pre-initialize the raw and shadow slots: a store writes only its
					// own pair, so every slot must be DEFINED on every path or a later
					// read fails verification at a merge.
					ctx.emit(Opcode.LCONST_0);
					ctx.emit(Opcode.LSTORE);
					ctx.emit(longSlot);
					ctx.emit(Opcode.ACONST_NULL);
					ctx.emit(Opcode.ASTORE);
					ctx.emit(shadowSlot);
					JvmIntFusionCompiler.compileRawStore(pairList.get(1), ctx, className, rawLocal);
					ctx.rawLocals.put(name, rawLocal);
					ctx.locals.remove(name);
					ctx.localIntLambdas.remove(name);
					ctx.boxedVars.remove(name);
					boundInThisLet.add(name);
					continue;
				}
				boundInThisLet.add(name);
				if (capturedInLet.contains(name)) {
					ctx.emit(Opcode.ICONST_1);
					ctx.emit(Opcode.ANEWARRAY);
					ctx.emitU2(ctx.objectClass.index());
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.ICONST_0);
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
					ctx.emit(Opcode.AASTORE);
				}
				else {
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
				}
				int slot = ctx.allocLocal(name);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(slot);
				ctx.rawLocals.remove(name);
				ctx.localIntLambdas.remove(name);
				// A let-bound lambda whose body is a closed integer tree over its
				// parameters (the flet lowering's __FLETn_f shape) registers for
				// fused-call substitution; the closure value in the slot is untouched,
				// so every non-fused use still works (.kb/jvm-int-fusion.md).
				if (JvmIntFusionCompiler.enabled(ctx) && pairList.get(1) instanceof LispCons initCons) {
					JvmIntFusionCompiler.LocalIntLambda lil = JvmIntFusionCompiler.eligibleLocalLambda(initCons, ctx);
					if (lil != null) {
						ctx.localIntLambdas.put(name, lil);
					}
				}
				// The boxed set tracks names, so this binding's boxedness must
				// REPLACE a shadowed outer binding's: a raw closure stored under a
				// name whose outer binding was boxed would otherwise be cell-read in
				// the body. Updated only after the init compiled, so the init
				// (evaluated in the outer scope, e.g. a lambda capturing the
				// same-named outer variable) still sees the outer boxedness.
				if (capturedInLet.contains(name)) {
					ctx.boxedVars.add(name);
				}
				else {
					ctx.boxedVars.remove(name);
				}
			}
		}
		final List<int[]> restores = dynamicRestores;
		// Restore each dynamically bound special to its saved previous cell (possibly
		// null = no binding on this thread). This runs with the body's result on top of
		// the stack; each restore is stack-neutral (getstatic tl; aload cell;
		// ThreadLocal.set) so the result is preserved.
		Runnable afterBody = () -> {
			if (restores != null) {
				int tlSetIndex = Objects.requireNonNull(ctx.dynVars).tlSet().index();
				for (int i = restores.size() - 1; i >= 0; i--) {
					int[] restore = restores.get(i);
					ctx.emit(Opcode.GETSTATIC);
					ctx.emitU2(restore[0]);
					ctx.emit(Opcode.ALOAD);
					ctx.emit(restore[1]);
					ctx.emit(Opcode.INVOKEVIRTUAL);
					ctx.emitU2(tlSetIndex);
					ctx.specialBindScopes.pop();
				}
			}
			ctx.locals = savedLocals;
			ctx.boxedVars = savedBoxedVars;
			ctx.rawLocals = savedRawLocals;
			ctx.localIntLambdas = savedLocalIntLambdas;
			ctx.nextLocal = savedNextLocal;
		};
		// A body-less (let ((x 1))) has no value to push and no split point; it keeps
		// the historical emission rather than joining the spine.
		if (tail != null && parts.size() > 2) {
			List<JvmBodyOutliner.Item> items = new ArrayList<>();
			for (int i = 2; i < parts.size(); i++) {
				if (i > 2) {
					items.add(new JvmBodyOutliner.PopValue());
				}
				items.add(new JvmBodyOutliner.ValueForm(parts.get(i)));
			}
			items.add(new JvmBodyOutliner.Cleanup(afterBody));
			tail.pushFront(items);
			return;
		}
		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.emit(Opcode.POP);
			}
			JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
		}
		afterBody.run();
	}

}
