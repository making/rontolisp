package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.lang.classfile.constantpool.FieldRefEntry;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.LetBoundDesignators;
import am.ik.rontolisp.compiler.ParallelLetStaging;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code let} special form.
 *
 * <p>
 * A binding whose name is a special (dynamically bound) variable establishes a dynamic
 * binding. In a program that runs Lisp code on one thread only it is a SHALLOW binding:
 * the special's {@code _g$} field is saved in a temp and set to the init value, so every
 * read stays one {@code getstatic}. Where another thread can run Lisp code it is
 * THREAD-SCOPED over the special's {@code _d$} ThreadLocal (interpreter parity: two
 * http-handler request threads binding the same special must not clobber each other):
 * {@code _dbind} installs a fresh cell holding the init value and answers the previous
 * cell, saved in a temp; the new value is visible (via the dynamic-first {@code _dget}
 * read) to any function called during the body on THIS thread, and other threads keep
 * reading the {@code _g$} global default ({@code Ctx.threadScopedSpecials}). Either way
 * the saved state is put back on EVERY exit from the body: the body is a protected region
 * of the {@code unwind-protect} machinery ({@link JvmUnwindProtectCompiler.Region}) whose
 * cleanups are {@code %dyn-restore} forms, so an error unwind, a cross-lambda exit and a
 * {@code return}/{@code return-from}/ {@code go} escape all restore, in CL's
 * innermost-first order against any cleanup nested around or inside the binding -- the
 * interpreter's {@code finally}.
 */
final class JvmLetCompiler {

	private JvmLetCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, null, false);
	}

	/**
	 * Compiles a {@code let} whose VALUE is discarded, leaving nothing on the operand
	 * stack: every body form compiles for effect, so a final form that is a raw-local
	 * assignment stores and stops ({@link JvmSetqCompiler#compileForEffect}) instead of
	 * boxing a value the caller pops.
	 */
	static void compileForEffect(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, null, true);
	}

	/**
	 * @param tail the tail spine this {@code let} ends, or null. When it has one, the
	 * body forms JOIN the spine and the after-the-body work (the dynamic-binding
	 * restores, then the compile-time scope restore) becomes a {@code Cleanup} item on it
	 * -- the same emission in the same order, with a split point between any two body
	 * forms ({@link JvmBodyOutliner}).
	 */
	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, JvmBodyOutliner.@Nullable Tail tail) {
		compile(cons, ctx, className, tail, false);
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className,
			JvmBodyOutliner.@Nullable Tail tail, boolean forEffect) {
		// A binding that only holds a literal designator for the body's funcall sites is
		// propagated into them and dropped, so the designator never becomes a VALUE here
		// (LetBoundDesignators; the WASM twin does the same).
		LispCons letForm = LetBoundDesignators.propagate(cons, ctx.specialVars, ctx.functions.keySet());
		// A let is PARALLEL, and the loop below binds one variable at a time: a let in
		// which a later init reads an earlier variable's name, or runs code after a
		// special binding, is staged through temporaries first (ParallelLetStaging; the
		// WASM twin does the same). Any other let comes back as the same object.
		letForm = ParallelLetStaging.stage(letForm, ctx.specialVars);
		List<LispVal> parts = letForm.toList();
		// A bare symbol entry is an init-less binding to nil.
		LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		Set<String> savedBoxedVars = new HashSet<>(ctx.boxedVars);
		Map<String, JvmIntFusionCompiler.RawLocal> savedRawLocals = new HashMap<>(ctx.rawLocals);
		Map<String, Integer> savedRawDoubleLocals = new HashMap<>(ctx.rawDoubleLocals);
		Set<String> savedDeclaredDoubles = ctx.declaredDoubles;
		Map<String, JvmIntFusionCompiler.LocalIntLambda> savedLocalIntLambdas = new HashMap<>(ctx.localIntLambdas);
		int savedNextLocal = ctx.nextLocal;
		// The body-head float declarations (.kb/declarations-type-checks.md): a BOUND
		// declaration makes its binding eligible for a raw double slot below; bound and
		// free ones together route the body's arithmetic
		// (JvmLispCompiler.containsDouble). Registered for the BODY after the bindings
		// compiled (a free declaration covers the outer binding within this body only,
		// never this let's own inits); specials are never registered.
		Set<String> declaredFloats = am.ik.rontolisp.compiler.DeclaredScalarTypes
			.declaredDoubles(parts.subList(2, parts.size()), ctx.closRegistry);
		boolean bodyDefinesFunction = definesNestedFunction(parts.subList(2, parts.size()));
		// The lexical bindings take part in capture analysis. A special binding is the
		// thread's dynamic cell alone, which a closure built in the body reads when it
		// runs -- never a captured copy (.kb/dynamic-special-variables.md).
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				String name = ((LispSymbol) ((LispCons) binding).toList().get(0)).name();
				if (!ctx.specialVars.contains(name)) {
					letVarNames.add(name);
				}
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(parts.subList(2, parts.size()), letVarNames,
				ctx.functions.keySet(), ctx.captureMemo);
		ctx.boxedVars = new HashSet<>(ctx.boxedVars);
		// Each dynamic (special) binding established here: {homeFieldIndex, saveSlot}.
		// Restored (reverse order) after the body, before the scope is popped.
		List<int[]> dynamicRestores = null;
		Set<String> boundInThisLet = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (ctx.specialVars.contains(name)) {
					// The dynamic binding: the one every read of the name resolves to
					// while it is active -- the body's, a called function's and a
					// closure's alike -- and a setq writes
					// (JvmSetqCompiler.emitGlobalStore). No lexical slot: a closure that
					// runs after this extent reads the binding active then.
					FieldRefEntry home = bindingHome(name, ctx);
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
					int saveSlot = ctx.allocTemp();
					if (ctx.threadScopedSpecials) {
						// This thread's binding, pushed via _dbind, which answers the
						// previous cell (null: no binding on this thread).
						ctx.body.getstatic(home).swap();
						ctx.body.invokestatic(Objects.requireNonNull(ctx.dynVars).dbind());
						ctx.body.astore(saveSlot);
					}
					else {
						// Shallow binding: the _g$ field saved, then set.
						ctx.body.getstatic(home).astore(saveSlot).putstatic(home);
					}
					if (dynamicRestores == null) {
						dynamicRestores = new ArrayList<>();
					}
					dynamicRestores.add(new int[] { home.index(), saveSlot });
					// A special is never a lexical: nothing of the name answers for the
					// body but its dynamic binding.
					ctx.locals.remove(name);
					ctx.rawLocals.remove(name);
					ctx.rawDoubleLocals.remove(name);
					ctx.localIntLambdas.remove(name);
					ctx.boxedVars.remove(name);
					continue;
				}
				// A declared-float binding kept in a raw double slot
				// (.kb/jvm-double-arithmetic.md): a plain lexical covered by a bound
				// (declare (type double-float ...)) in this body. The slot is always
				// authoritative -- the init and every assignment land raw, through the
				// strict cast when the emitter cannot prove the value
				// (.kb/declarations-type-checks.md: a false declaration is a
				// deterministic ClassCastException, never a coerced value). A nested
				// defun in the body reaches the name through its global backing store,
				// so such a body declines, like the raw longs.
				if (declaredFloats.contains(name) && !ctx.specialVars.contains(name) && !capturedInLet.contains(name)
						&& !boundInThisLet.contains(name) && !ctx.globals.contains(name) && !ctx.dynamic
						&& !bodyDefinesFunction && ctx.nextLocal + 2 <= 250) {
					JvmSetqCompiler.compileRawDoubleValue(pairList.get(1), ctx, className);
					int doubleSlot = ctx.allocTemp();
					ctx.allocTemp();
					ctx.body.dstore(doubleSlot);
					ctx.rawDoubleLocals.put(name, doubleSlot);
					ctx.locals.remove(name);
					ctx.rawLocals.remove(name);
					ctx.localIntLambdas.remove(name);
					ctx.boxedVars.remove(name);
					boundInThisLet.add(name);
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
					JvmIntFusionCompiler.RawLocal rawLocal = JvmIntFusionCompiler.RawLocal.slots(longSlot, shadowSlot,
							flagSlot);
					// Pre-initialize the raw and shadow slots: a store writes only its
					// own pair, so every slot must be DEFINED on every path or a later
					// read fails verification at a merge.
					ctx.body.lconst_0().lstore(longSlot).aconst_null().astore(shadowSlot);
					JvmIntFusionCompiler.compileRawStore(pairList.get(1), ctx, className, rawLocal);
					ctx.rawLocals.put(name, rawLocal);
					ctx.locals.remove(name);
					ctx.rawDoubleLocals.remove(name);
					ctx.localIntLambdas.remove(name);
					ctx.boxedVars.remove(name);
					boundInThisLet.add(name);
					continue;
				}
				boundInThisLet.add(name);
				if (capturedInLet.contains(name)) {
					ctx.body.iconst_1().anewarray(ctx.objectClass).dup().iconst_0();
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
					ctx.body.aastore();
				}
				else {
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
				}
				int slot = ctx.allocLocal(name);
				ctx.body.astore(slot);
				ctx.rawLocals.remove(name);
				ctx.rawDoubleLocals.remove(name);
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
		// The float declarations take effect for the BODY: shadowed outer names drop
		// out whatever this let bound them as; the declared names (bound and free,
		// specials excluded) route the body's arithmetic. The let's own inits compiled
		// above under the OUTER set, as CL scopes free declarations.
		Set<String> bodyDeclaredDoubles = new HashSet<>(savedDeclaredDoubles);
		bodyDeclaredDoubles.removeAll(letVarNames);
		for (String name : declaredFloats) {
			if (!ctx.specialVars.contains(name)) {
				bodyDeclaredDoubles.add(name);
			}
		}
		ctx.declaredDoubles = bodyDeclaredDoubles;
		// A body under dynamic bindings is a PROTECTED REGION whose cleanups restore
		// each special to its saved state (the previous value, or the previous cell --
		// possibly null = no binding on this thread), innermost first: the unwind-protect
		// machinery then restores on every exit channel -- normal completion, an error
		// unwind (caught in this frame or across a callee's), a cross-lambda exit, and a
		// return/return-from/go escape, which inlines the escaped scopes' cleanups in
		// CL's innermost-first order (.kb/dynamic-special-variables.md). A body-less let
		// has no region: nothing can exit it abnormally, and its restores run straight
		// after the bindings.
		boolean hasBody = parts.size() > 2;
		JvmUnwindProtectCompiler.Region region = dynamicRestores == null || !hasBody ? null
				: JvmUnwindProtectCompiler.Region.open(restoreForms(dynamicRestores), ctx, className, !forEffect);
		final List<int[]> restores = dynamicRestores;
		Runnable afterBody = () -> {
			if (region != null) {
				region.close();
			}
			else if (restores != null) {
				for (int i = restores.size() - 1; i >= 0; i--) {
					emitRestore(restores.get(i)[0], restores.get(i)[1], ctx);
				}
			}
			ctx.locals = savedLocals;
			ctx.boxedVars = savedBoxedVars;
			ctx.rawLocals = savedRawLocals;
			ctx.rawDoubleLocals = savedRawDoubleLocals;
			ctx.declaredDoubles = savedDeclaredDoubles;
			ctx.localIntLambdas = savedLocalIntLambdas;
			ctx.nextLocal = savedNextLocal;
		};
		// A body-less (let ((x 1))) has no value to push and no split point; it keeps
		// the historical emission rather than joining the spine.
		if (tail != null && parts.size() > 2) {
			List<JvmBodyOutliner.Item> items = new ArrayList<>();
			for (int i = 2; i < parts.size(); i++) {
				if (i < parts.size() - 1) {
					// A non-final body form's value is discarded: the effect item lets
					// a statement assignment store and stop (the raw-local shapes,
					// .kb/jvm-int-fusion.md / .kb/jvm-double-arithmetic.md).
					items.add(new JvmBodyOutliner.EffectForm(parts.get(i)));
				}
				else {
					items.add(new JvmBodyOutliner.ValueForm(parts.get(i)));
				}
			}
			// The Cleanup's runtime flag tells the tail-mark walk whether the restore
			// emits code: a dynamic binding's restore must run inside the tail's extent,
			// so the tail above it keeps a real call (JvmTailBounce).
			items.add(new JvmBodyOutliner.Cleanup(afterBody, region != null || restores != null));
			tail.pushFront(items, ctx);
			return;
		}
		LispVal savedMark = ctx.tailMark;
		LispVal savedExit = ctx.exitMark;
		// With no restore after the body, every body form is on the exit chain when the
		// let is (Ctx.exitMark).
		boolean exits = region == null && restores == null && JvmReturnCompiler.onExitChain(cons, ctx);
		for (int i = 2; i < parts.size(); i++) {
			ctx.exitMark = exits ? parts.get(i) : null;
			if (forEffect || i < parts.size() - 1) {
				JvmExprCompiler.compileForEffect(parts.get(i), ctx, className);
			}
			else {
				// The last body form's value is this let's, so the trampoline's tail
				// mark -- laid here when this let is the method's tail -- re-lays onto
				// it; a let whose restore emits code keeps real calls, the restore
				// belonging inside the tail's extent.
				ctx.tailMark = savedMark == cons && region == null && restores == null ? parts.get(i) : null;
				JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			}
		}
		ctx.tailMark = savedMark;
		ctx.exitMark = savedExit;
		if (!hasBody && !forEffect) {
			// CLHS: a body-less let/let* returns nil (the loop above pushed nothing).
			ctx.body.aconst_null();
		}
		afterBody.run();
	}

	/**
	 * The {@code (%dyn-restore homeFieldIndex saveSlot)} cleanup forms of a body under
	 * dynamic bindings, innermost first -- the order the bindings must be undone in.
	 * @param dynamicRestores {@code {homeFieldIndex, saveSlot}} per binding, in binding
	 * order
	 * @return the cleanup forms
	 */
	private static List<LispVal> restoreForms(List<int[]> dynamicRestores) {
		List<LispVal> forms = new ArrayList<>();
		for (int i = dynamicRestores.size() - 1; i >= 0; i--) {
			int[] restore = dynamicRestores.get(i);
			forms.add(new LispCons(new LispSymbol(LispNames.DYN_RESTORE_INTERNAL), new LispCons(
					new LispInteger(restore[0]), new LispCons(new LispInteger(restore[1]), LispNil.INSTANCE))));
		}
		return forms;
	}

	/**
	 * Compiles the internal {@code (%dyn-restore homeFieldIndex saveSlot)} form
	 * ({@link LispNames#DYN_RESTORE_INTERNAL}): restores the binding of one special to
	 * the state saved at the binding site, and yields nil. Built by {@link #restoreForms}
	 * only, as the cleanups of a special {@code let}'s protected region, so it reaches
	 * the expression compiler on every exit path the region emits (normal, handler, the
	 * copies inlined at a {@code return}/{@code go}). Statement position
	 * ({@link JvmExprCompiler#compileForEffect}) calls {@link #emitRestore} directly and
	 * skips the nil.
	 */
	static void compileDynRestore(LispCons cons, JvmLispCompiler.Ctx ctx) {
		emitRestoreForEffect(cons, ctx);
		ctx.body.aconst_null();
	}

	/** The {@code %dyn-restore} form compiled for effect: the restore, no value. */
	static void emitRestoreForEffect(LispCons cons, JvmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		emitRestore((int) ((LispInteger) parts.get(1)).value(), (int) ((LispInteger) parts.get(2)).value(), ctx);
	}

	/**
	 * The field a special's binding lives in: its {@code _d$} ThreadLocal where a binding
	 * is thread-scoped ({@code Ctx.threadScopedSpecials}), else its {@code _g$} global,
	 * which the binding saves, sets and restores.
	 * @param name the special
	 * @param ctx the compilation context
	 * @return the field
	 */
	static FieldRefEntry bindingHome(String name, JvmLispCompiler.Ctx ctx) {
		if (ctx.threadScopedSpecials) {
			JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
			FieldRefEntry tlField = dyn == null ? null : dyn.fields().get(name);
			if (tlField == null) {
				// The pre-pass promised every dynamically-bound special a ThreadLocal; a
				// miss here must fail the compile loudly, never fall back to a silently
				// process-global binding.
				throw new IllegalStateException(
						"special variable " + name + " is dynamically bound here but has no thread-local store"
								+ " (SpecialVarCollector.collectDynamicallyBound missed this binding form)");
			}
			return tlField;
		}
		if (ctx.rawGlobals.containsKey(name)) {
			// A raw global's value may sit in its unboxed field, which a save of _g$
			// would not see; the pre-pass keeps every dynamically-bound special out of
			// that representation (JvmRawGlobals).
			throw new IllegalStateException("special variable " + name + " is dynamically bound here but carries the"
					+ " unboxed global representation (SpecialVarCollector.collectDynamicallyBound missed this"
					+ " binding form)");
		}
		return Objects.requireNonNull(ctx.globalFields.get(name));
	}

	/**
	 * Emits one binding restore -- {@code getstatic tl; aload cell; ThreadLocal.set} for
	 * a thread-scoped binding, {@code aload saved; putstatic global} for a shallow one --
	 * stack-neutral, so it may run over a value the surrounding code keeps on the stack.
	 * @param homeFieldIndex the special's {@link #bindingHome} field constant
	 * @param saveSlot the local holding the previous cell (possibly null) or value
	 * @param ctx the compilation context
	 */
	private static void emitRestore(int homeFieldIndex, int saveSlot, JvmLispCompiler.Ctx ctx) {
		FieldRefEntry home = (FieldRefEntry) ctx.cp.entryAt(homeFieldIndex);
		if (ctx.threadScopedSpecials) {
			ctx.body.getstatic(home).aload(saveSlot).invokevirtual(Objects.requireNonNull(ctx.dynVars).tlSet());
		}
		else {
			ctx.body.aload(saveSlot).putstatic(home);
		}
	}

	/**
	 * Whether the body defines a nested named function ({@code defun} and its async
	 * forms), which lowers to a closure over the enclosing bindings AND reaches them
	 * through the global backing store -- the same veto
	 * {@link JvmIntFusionCompiler#rawBindingEligible} applies to the raw longs. Quoted
	 * data is skipped.
	 */
	private static boolean definesNestedFunction(List<LispVal> bodyForms) {
		for (LispVal form : bodyForms) {
			if (definesNestedFunction(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean definesNestedFunction(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (am.ik.rontolisp.LispNames.isQuote(head.name())) {
				return false;
			}
			if (am.ik.rontolisp.LispNames.DEFUN.equals(head.name())
					|| am.ik.rontolisp.LispNames.ASYNC_DEFUN.equals(head.name())
					|| am.ik.rontolisp.LispNames.ASYNC_DEFUN_QUALIFIED.equals(head.name())) {
				return true;
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			if (definesNestedFunction(cell.car())) {
				return true;
			}
			cur = cell.cdr();
		}
		return false;
	}

}
