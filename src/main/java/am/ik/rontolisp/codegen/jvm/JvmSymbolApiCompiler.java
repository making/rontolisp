package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.jvm.ConstantPool;

/**
 * Compiles the runtime symbol API: {@code symbol-name}, {@code intern},
 * {@code find-symbol}, {@code make-symbol}, {@code boundp}, {@code fboundp} and
 * {@code symbol-value}.
 *
 * <p>
 * The pure converters are plain string operations on the shared value representation (a
 * symbol is a bare String, a string carries surrounding quotes): {@code symbol-name}
 * wraps the display text in quotes exactly like {@code princ-to-string}, {@code intern}
 * strips the quotes, {@code make-symbol} prepends the {@code #:} uninterned marker.
 * {@code find-symbol} folds at compile time (literal-only, like {@code symbol-function}).
 * {@code boundp}/{@code symbol-value} resolve against the eval runtime's global
 * environment mirror {@code _genv} (so they see top-level globals only, like CL's
 * dynamic-only {@code symbol-value}), and a computed {@code fboundp} probes {@code _fenv}
 * then the compiled-function registry {@code _lookup}; all three force {@code usesEval}
 * in {@link JvmLispCompiler}.
 */
final class JvmSymbolApiCompiler {

	private JvmSymbolApiCompiler() {
	}

	/** symbol-name: the display text wrapped in quotes (same emission as princ). */
	static void compileSymbolName(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.SYMBOL_NAME);
		JvmPrincToStringCompiler.emitToString(parts.get(1), ctx.lispToDisplayString, ctx, className);
	}

	/**
	 * string: the CL string-designator coercion, and the single definition of it every
	 * designator POSITION routes through (the {@code string=} operands, the
	 * {@code %string-compare} walk behind the {@code string<} family, the
	 * {@code string-trim} / case-fold arguments). A compile-time-known designator folds
	 * to its constant; a computed one gets
	 * {@link LispMacroExpander#strictStringDesignatorForm} -- the guarded princ coercion,
	 * which type-checks like the interpreter instead of stringifying anything handed to
	 * it.
	 */
	static void compileString(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.STRING);
		// A keyword's package colon is a marker, not part of its name: (string :html) is
		// "HTML" (matches CL; cl-who relies on it to emit <html>, not <:html>).
		String literal = LispMacroExpander.literalStringDesignator(parts.get(1));
		if (literal != null) {
			JvmEmitHelper.compileStringLiteral(new LispString(literal).literal(), ctx);
			return;
		}
		// Computed: a string answers ITSELF -- a quote-framed literal and a mutable
		// character vector alike (CLHS string of a string, and what the interpreter
		// does); anything else takes the guarded coercion below. One evaluation --
		// the value parks in a temp both arms read back.
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.body.astore(tempSlot);
		// stringp (proper or charvec)?
		ctx.body.aload(tempSlot);
		JvmEmitHelper.emitSharedCall(ctx, className, "_pStringp", 1, helper -> {
			// The helper Ctx shares the constant pool; the check is stringp's own.
			JvmStringpCompiler.emitStringpCheck(helper, 0);
		});
		MethodCode.Label notStringp = ctx.body.newLabel();
		ctx.body.ifnull(notStringp);
		ctx.body.aload(tempSlot);
		MethodCode.Label done = ctx.body.newLabel();
		ctx.body.goto_(done);
		ctx.body.labelBinding(notStringp);
		// Slow: the guarded coercion. A symbol (nil and every bare String --
		// quoted ones stringp above) or a character renders through display and
		// reframes; anything else signals exactly like the strict designator form
		// this replaces.
		// nil is a symbol, like every bare String.
		ctx.body.aload(tempSlot);
		MethodCode.Label isNil = ctx.body.newLabel();
		ctx.body.ifnull(isNil);
		ctx.body.aload(tempSlot).instanceOf(ctx.stringClass);
		MethodCode.Label notSymbol = ctx.body.newLabel();
		ctx.body.ifeq(notSymbol);
		MethodCode.Label coerceStr = ctx.body.newLabel();
		ctx.body.goto_(coerceStr);
		ctx.body.labelBinding(isNil);
		MethodCode.Label coerceNil = ctx.body.newLabel();
		ctx.body.goto_(coerceNil);
		ctx.body.labelBinding(notSymbol);
		// character?
		ctx.body.aload(tempSlot).instanceOf(JvmEmitHelper.charArrayClass(ctx));
		MethodCode.Label notChar = ctx.body.newLabel();
		ctx.body.ifeq(notChar);
		ctx.body.labelBinding(coerceStr);
		ctx.body.labelBinding(coerceNil);
		// render and reframe: "\"" + display + "\""
		ctx.body.aload(tempSlot).invokestatic(ctx.lispToDisplayString);
		emitRequote(ctx);
		MethodCode.Label done2 = ctx.body.newLabel();
		ctx.body.goto_(done2);
		ctx.body.labelBinding(notChar);
		emitStringDesignatorThrow(tempSlot, ctx);
		ctx.body.labelBinding(done);
		ctx.body.labelBinding(done2);
	}

	// "\"" + content + "\"", the quote frame a string VALUE carries. Display answers
	// a String already, so this is two concats.
	private static void emitRequote(JvmLispCompiler.Ctx ctx) {
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.body.swap().invokevirtual(concat);
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.body.invokevirtual(concat);
	}

	// throw new RuntimeException("string expects a string designator, got: " + value)
	// -- the strict designator form's own wording.
	private static void emitStringDesignatorThrow(int tempSlot, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry ctor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry valueOf = ctx.cp.methodRef(ctx.stringClass, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
		MethodRefEntry concat = ctx.cp.methodRef(ctx.stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral(LispNames.STRING + " expects a string designator, got: ", ctx);
		ctx.body.aload(tempSlot);
		// princ-render the value the way ~s would, so the report reads the same.
		ctx.body.invokestatic(ctx.lispToDisplayString).invokestatic(valueOf);
		ctx.body.invokevirtual(concat).invokespecial(ctor).athrow();
	}

	/** intern: strip the surrounding quotes from the runtime string. */
	static void compileIntern(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> full = cons.toList();
		if (full.size() == 3) {
			// (intern name pkg): the canonical-spelling lowering shared with the 2-arg
			// find-symbol (an unknown package is a call-time signal, or -- when the
			// program can create packages -- a runtime-table lookup first).
			JvmExprCompiler.compileExpr(LispMacroExpander.expandInternInPackage(cons, ctx.packageTable,
					ctx.usesRuntimePackages, ctx.functions::containsKey, ctx.bakedSymbolAccess), ctx, className);
			return;
		}
		List<LispVal> parts = requireArgs(cons, 1, LispNames.INTERN);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// A mutable character vector is a string here ((intern (make-string n)) after
		// the buffer is filled), so normalize before the quote strip casts to String.
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		JvmEmitHelper.emitSharedCall(ctx, className, "_internName", 1, helper -> {
			helper.body.aload(0);
			emitStripQuotes(helper);
			emitNilSpellingToNil(helper);
		});
	}

	/**
	 * Maps the stripped name on the stack to the symbol it names: the spelling
	 * {@code NIL} is the {@code nil} singleton ({@code null}), not a symbol of that name.
	 * {@code T} needs no arm -- {@code t} IS the string {@code "T"} on this backend, and
	 * {@code eq} compares strings by content.
	 */
	private static void emitNilSpellingToNil(JvmLispCompiler.Ctx ctx) {
		ctx.body.dup();
		// The literal is compared, never produced, so it is no designator the
		// dispatch gate's name probes must see.
		JvmEmitHelper.compileUnspelledLiteral("NIL", ctx);
		ctx.body.swap().invokevirtual(ctx.objectEquals);
		MethodCode.Label keep = ctx.body.newLabel();
		ctx.body.ifeq(keep);
		ctx.body.pop().aconst_null();
		ctx.body.labelBinding(keep);
	}

	/** make-symbol: {@code "#:".concat(content)} -- the gensym uninterned convention. */
	static void compileMakeSymbol(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.MAKE_SYMBOL);
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		JvmEmitHelper.compileStringLiteral("#:", ctx);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		emitStripQuotes(ctx);
		ctx.body.invokevirtual(concat);
	}

	/**
	 * find-symbol: a LITERAL name folds at compile time against the compile-time view of
	 * the image (cl symbols, keywords, Pass-1 user defuns); a computed one lowers to
	 * {@code intern}, which is the lookup under the name-based symbol model.
	 */
	static void compileFindSymbol(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (cons.toList().size() == 3) {
			LispVal inPackage = LispMacroExpander.expandFindSymbolInPackage(cons, ctx.packageTable,
					ctx.usesRuntimePackages, ctx.functions::containsKey, ctx.bakedSymbolAccess);
			if (inPackage == null) {
				throw new UnsupportedOperationException(LispNames.FIND_SYMBOL
						+ " needs a literal package designator in compiled mode: " + cons.print());
			}
			JvmExprCompiler.compileExpr(inPackage, ctx, className);
			return;
		}
		List<LispVal> parts = requireArgs(cons, 1, LispNames.FIND_SYMBOL);
		if (!(parts.get(1) instanceof LispString str)) {
			JvmExprCompiler.compileExpr(LispMacroExpander.computedFindSymbol(parts.get(1)), ctx, className);
			return;
		}
		LispVal found = LispMacroExpander.foldLiteralFindSymbol(str.value(), ctx.userDefunNames);
		if (found instanceof LispSymbol sym) {
			JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
		}
		else {
			JvmExprCompiler.compileExpr(found, ctx, className);
		}
	}

	/**
	 * {@code %find-symbol-status}: a compile-time constant, decided by the shared fold so
	 * both backends answer the same keyword (see
	 * {@link LispMacroExpander#expandFindSymbolStatus}).
	 */
	static void compileFindSymbolStatus(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The answer is a keyword or nil, both self-evaluating: no quote needed.
		JvmExprCompiler.compileExpr(LispMacroExpander.expandFindSymbolStatus(cons, ctx.packageTable, ctx.userDefunNames,
				ctx.usesRuntimePackages, ctx.functions::containsKey, ctx.bakedSymbolAccess), ctx, className);
	}

	/**
	 * boundp. A global whose variable carries its bound-ness
	 * ({@link JvmDynVarRuntimeBuilder#unboundMarker}) is answered by it
	 * ({@link LispMacroExpander#dynamicFirstBoundp}): a literal one by
	 * {@code %global-boundp}, a computed name through the shared dispatch over them.
	 * Every other name, and every name in a program without such a global, takes the raw
	 * probe.
	 */
	static void compileBoundp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmDynVarRuntimeBuilder.UnboundMarker marker = ctx.unboundMarker;
		if (marker != null) {
			LispVal tracked = LispMacroExpander.dynamicFirstBoundp(cons, marker.globals(), ctx.specialVars,
					ctx.functions.containsKey(LispNames.BOUNDP_DYNAMIC));
			if (tracked != null) {
				JvmExprCompiler.compileExpr(tracked, ctx, className);
				return;
			}
		}
		compileBoundpRaw(cons, ctx, className);
	}

	/**
	 * {@code (%global-boundp 'G)}: whether the global's variable holds a value. A special
	 * whose dynamic binding is thread-scoped asks {@code _dbound} over its ThreadLocal
	 * and global -- this thread's binding, else a global that is not the UNBOUND marker;
	 * any other global, a shallow binding's included, compares its field with the marker.
	 */
	static void compileGlobalBoundp(LispCons cons, JvmLispCompiler.Ctx ctx) {
		String name = ((LispSymbol) ((LispCons) cons.toList().get(1)).toList().get(1)).name();
		JvmDynVarRuntimeBuilder.UnboundMarker marker = ctx.unboundMarker;
		FieldRefEntry global = ctx.globalFields.get(name);
		if (marker == null || global == null || !marker.globals().contains(name)) {
			throw new IllegalStateException("global " + name + " does not carry its bound-ness in its variable ("
					+ LispNames.BOUNDP + " of it was lowered to " + LispNames.GLOBAL_BOUNDP + ")");
		}
		JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
		FieldRefEntry tlField = dyn == null ? null : dyn.fields().get(name);
		if (tlField != null) {
			ctx.body.getstatic(tlField);
			ctx.body.getstatic(global);
			ctx.body.invokestatic(java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(dyn).dbound()));
			return;
		}
		ctx.body.getstatic(global);
		ctx.body.getstatic(marker.field());
		MethodCode.Label unbound = ctx.body.newLabel();
		ctx.body.if_acmpeq(unbound);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label end = ctx.body.newLabel();
		ctx.body.goto_(end);
		ctx.body.labelBinding(unbound);
		ctx.body.aconst_null();
		ctx.body.labelBinding(end);
	}

	/**
	 * The raw {@code boundp} emission: nil/t/keyword are self-bound, otherwise probe the
	 * {@code _genv} mirror -- also reachable as {@code %boundp-raw}, the fallback arm of
	 * the dispatch above.
	 */
	static void compileBoundpRaw(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.BOUNDP);
		int tempSlot = compileArgToTemp(parts.get(1), ctx, className);
		// nil -> t
		ctx.body.aload(tempSlot);
		MethodCode.Label ifNotNil = ctx.body.newLabel();
		ctx.body.ifnonnull(ifNotNil);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEnd1 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd1);
		ctx.body.labelBinding(ifNotNil);
		// t / keyword -> t
		MethodCode.Label notSelfBound = emitSelfBoundCheck(tempSlot, ctx);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEnd2 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd2);
		ctx.body.labelBinding(notSelfBound);
		// _envLookup(name, _genv) != null -> t
		emitGenvLookup(tempSlot, ctx, className);
		MethodCode.Label ifUnbound = ctx.body.newLabel();
		ctx.body.ifnull(ifUnbound);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEnd3 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd3);
		ctx.body.labelBinding(ifUnbound);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEnd1);
		ctx.body.labelBinding(gotoEnd2);
		ctx.body.labelBinding(gotoEnd3);
	}

	/**
	 * symbol-value: nil/t/keyword evaluate to themselves, otherwise read {@code _genv} --
	 * except a special, which is read through its variable
	 * ({@link LispMacroExpander#dynamicFirstSymbolValue}): a literal special reads the
	 * variable (the {@code _dget} read), a computed name calls the shared dispatch over
	 * the special set, so an active {@code progv}/{@code let} binding -- and a
	 * {@code setq} or {@code set} inside its extent -- is answered, never the mirror,
	 * which no binding's restore touches. A program without specials keeps the raw
	 * emission.
	 */
	static void compileSymbolValue(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (!ctx.specialVars.isEmpty() && cons.toList().size() == 2) {
			JvmExprCompiler.compileExpr(LispMacroExpander.dynamicFirstSymbolValue(cons, ctx.specialVars,
					ctx.functions.containsKey(LispNames.SYMBOL_VALUE_DYNAMIC)), ctx, className);
			return;
		}
		compileSymbolValueRaw(cons, ctx, className);
	}

	/**
	 * The raw {@code symbol-value} emission (the {@code _genv} probe) -- also reachable
	 * as {@code %symbol-value-raw}, the fallback arm of the dynamic-first dispatch above.
	 */
	static void compileSymbolValueRaw(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.SYMBOL_VALUE);
		int tempSlot = compileArgToTemp(parts.get(1), ctx, className);
		// nil -> nil
		ctx.body.aload(tempSlot);
		MethodCode.Label ifNotNil = ctx.body.newLabel();
		ctx.body.ifnonnull(ifNotNil);
		ctx.body.aconst_null();
		MethodCode.Label gotoEnd1 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd1);
		ctx.body.labelBinding(ifNotNil);
		// t / keyword -> the symbol itself
		MethodCode.Label notSelfBound = emitSelfBoundCheck(tempSlot, ctx);
		ctx.body.aload(tempSlot);
		MethodCode.Label gotoEnd2 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd2);
		ctx.body.labelBinding(notSelfBound);
		// binding = _envLookup(name, _genv); null -> throw, else binding cdr
		emitGenvLookup(tempSlot, ctx, className);
		ctx.body.dup();
		MethodCode.Label ifUnbound = ctx.body.newLabel();
		ctx.body.ifnull(ifUnbound);
		ctx.body.checkcast(ctx.objectArrayClass).iconst_1().aaload();
		MethodCode.Label gotoEnd3 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd3);
		ctx.body.labelBinding(ifUnbound);
		ctx.body.pop();
		emitUnboundThrow(tempSlot, ctx);
		ctx.body.labelBinding(gotoEnd1);
		ctx.body.labelBinding(gotoEnd2);
		ctx.body.labelBinding(gotoEnd3);
	}

	/**
	 * fboundp: a literal quoted symbol folds at compile time (functions, macros, special
	 * forms, car/cdr compositions, user defuns); a computed argument probes the runtime
	 * {@code _fenv} then the compiled-function registry {@code _lookup} (so it sees
	 * functions only -- built-in macros and special forms exist solely at compile time).
	 *
	 * <p>
	 * When the program calls {@code fmakunbound} the fold is emitted BEHIND a tombstone
	 * probe of {@code _fenv}: a retired name must answer nil even at a literal call site,
	 * and only the runtime knows which names were retired. Programs without
	 * {@code fmakunbound} keep the bare constant.
	 */
	static void compileFboundp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.FBOUNDP);
		if (parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && ((LispCons) quoteForm.cdr()).car() instanceof LispSymbol sym) {
			String name = sym.name();
			boolean bound = PackageRegistry.specialOperatorNames().contains(name)
					|| PackageRegistry.clFunctionNames().contains(name) || LispNames.isCarCdrComposition(name)
					|| ctx.userDefunNames.contains(name) || ctx.functions.containsKey(name);
			MethodCode.Label foldEnd = ctx.usesFmakunbound ? emitTombstoneGuard(name, ctx, className) : null;
			if (bound) {
				JvmEmitHelper.compileTrue(ctx);
			}
			else {
				ctx.body.aconst_null();
			}
			if (foldEnd != null) {
				ctx.body.labelBinding(foldEnd);
			}
			return;
		}
		int tempSlot = compileArgToTemp(parts.get(1), ctx, className);
		// nil -> nil
		ctx.body.aload(tempSlot);
		MethodCode.Label ifNotNil = ctx.body.newLabel();
		ctx.body.ifnonnull(ifNotNil);
		ctx.body.aconst_null();
		MethodCode.Label gotoEnd1 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd1);
		ctx.body.labelBinding(ifNotNil);
		// binding = _envLookup(name, _fenv). A binding decides the answer on its own --
		// fmakunbound leaves a TOMBSTONE here (value cell null) that must SHADOW the
		// compiled registry probed below, or a retired name would answer t again.
		ctx.body.aload(tempSlot).getstatic(evalField(ctx, className, "_fenv"));
		ctx.body.invokestatic(envLookupRef(ctx, className)).dup();
		MethodCode.Label fenvMiss = ctx.body.newLabel();
		ctx.body.ifnull(fenvMiss);
		ctx.body.checkcast(ctx.objectArrayClass).iconst_1().aaload();
		MethodCode.Label tombstone = ctx.body.newLabel();
		ctx.body.ifnull(tombstone);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEnd2 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd2);
		ctx.body.labelBinding(tombstone);
		ctx.body.aconst_null();
		MethodCode.Label gotoEnd4 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd4);
		ctx.body.labelBinding(fenvMiss);
		ctx.body.pop();
		// _lookup(name) != null -> t
		ctx.body.aload(tempSlot);
		MethodRefEntry lookupRef = ctx.cp.methodRef(ctx.cp.classEntry(className), "_lookup",
				"(Ljava/lang/Object;)[Ljava/lang/Object;");
		ctx.body.invokestatic(lookupRef);
		MethodCode.Label registryMiss = ctx.body.newLabel();
		ctx.body.ifnull(registryMiss);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEnd3 = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd3);
		ctx.body.labelBinding(registryMiss);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEnd1);
		ctx.body.labelBinding(gotoEnd2);
		ctx.body.labelBinding(gotoEnd3);
		ctx.body.labelBinding(gotoEnd4);
	}

	/**
	 * Emits the tombstone half of {@code fboundp} for a literal name: when {@code _fenv}
	 * holds a binding for it, the answer is decided here (t when the value cell is set,
	 * nil when {@code fmakunbound} cleared it) and the caller's folded constant is
	 * skipped. Returns the label the caller binds at the end of the fold.
	 */
	private static MethodCode.Label emitTombstoneGuard(String name, JvmLispCompiler.Ctx ctx, String className) {
		JvmEmitHelper.compileStringLiteral(name, ctx);
		ctx.body.getstatic(evalField(ctx, className, "_fenv"));
		ctx.body.invokestatic(envLookupRef(ctx, className)).dup();
		MethodCode.Label noBinding = ctx.body.newLabel();
		ctx.body.ifnull(noBinding);
		ctx.body.checkcast(ctx.objectArrayClass).iconst_1().aaload();
		MethodCode.Label cleared = ctx.body.newLabel();
		ctx.body.ifnull(cleared);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label end = ctx.body.newLabel();
		ctx.body.goto_(end);
		ctx.body.labelBinding(cleared);
		ctx.body.aconst_null().goto_(end);
		ctx.body.labelBinding(noBinding);
		ctx.body.pop();
		// The caller's folded constant follows; both tombstone answers jump past it.
		return end;
	}

	/**
	 * fmakunbound: installs a TOMBSTONE binding (value cell {@code null}) for the name in
	 * the eval runtime's function namespace {@code _fenv}. That namespace is probed
	 * before the compiled-function registry, so every LATE-bound reference -- a computed
	 * {@code fboundp}, {@code #'name} through {@code eval}, {@code funcall} on the symbol
	 * -- sees the name undefined again, while a call site the compiler already bound
	 * directly (an {@code invokestatic} to the defun) keeps working: eager compilation
	 * cannot be undone. Returns the name, like CL.
	 */
	static void compileFmakunbound(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.FMAKUNBOUND);
		int nameSlot = compileArgToTemp(parts.get(1), ctx, className);
		ctx.body.aload(nameSlot).getstatic(evalField(ctx, className, "_fenv"));
		ctx.body.invokestatic(envLookupRef(ctx, className)).dup();
		MethodCode.Label create = ctx.body.newLabel();
		ctx.body.ifnull(create);
		// existing binding: clear its value cell
		ctx.body.checkcast(ctx.objectArrayClass).iconst_1().aconst_null().aastore();
		MethodCode.Label done = ctx.body.newLabel();
		ctx.body.goto_(done);
		ctx.body.labelBinding(create);
		ctx.body.pop();
		// _fenv = new Object[]{new Object[]{name, null}, _fenv}
		ctx.body.iconst_2().anewarray(ctx.objectClass).dup().iconst_0().iconst_2();
		ctx.body.anewarray(ctx.objectClass).dup().iconst_0().aload(nameSlot).aastore();
		ctx.body.aastore().dup().iconst_1().getstatic(evalField(ctx, className, "_fenv"));
		ctx.body.aastore().putstatic(evalField(ctx, className, "_fenv"));
		ctx.body.labelBinding(done);
		ctx.body.aload(nameSlot);
	}

	/**
	 * {@code (%set-symbol-function name value)} -- the write-side twin of
	 * {@code fmakunbound}'s tombstone: stores {@code value} into the name's {@code _fenv}
	 * binding (mutating an existing cell, else prepending a fresh binding), so every
	 * LATE-bound reference resolves to it -- and, for a name only ever defined this way,
	 * the injected forwarder defun's {@code %fenv-function} body. Leaves the value on the
	 * stack, the setf result.
	 */
	static void compileSetSymbolFunction(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 2, LispNames.SET_SYMBOL_FUNCTION_INTERNAL);
		int nameSlot = compileArgToTemp(parts.get(1), ctx, className);
		int valueSlot = compileArgToTemp(parts.get(2), ctx, className);
		ctx.body.aload(nameSlot).getstatic(evalField(ctx, className, "_fenv"));
		ctx.body.invokestatic(envLookupRef(ctx, className)).dup();
		MethodCode.Label create = ctx.body.newLabel();
		ctx.body.ifnull(create);
		// existing binding: overwrite its value cell
		ctx.body.checkcast(ctx.objectArrayClass).iconst_1().aload(valueSlot).aastore();
		MethodCode.Label done = ctx.body.newLabel();
		ctx.body.goto_(done);
		ctx.body.labelBinding(create);
		ctx.body.pop();
		// _fenv = new Object[]{new Object[]{name, value}, _fenv}
		ctx.body.iconst_2().anewarray(ctx.objectClass).dup().iconst_0().iconst_2();
		ctx.body.anewarray(ctx.objectClass).dup().iconst_0().aload(nameSlot).aastore();
		ctx.body.dup().iconst_1().aload(valueSlot).aastore().aastore().dup().iconst_1();
		ctx.body.getstatic(evalField(ctx, className, "_fenv")).aastore();
		ctx.body.putstatic(evalField(ctx, className, "_fenv"));
		ctx.body.labelBinding(done);
		ctx.body.aload(valueSlot);
	}

	/**
	 * {@code (set name value)} -- store {@code value} into the global variable
	 * {@code name} names, creating the binding when the name is unbound: the
	 * computed-name counterpart of {@code setq}, and what a run-time evaluator defines
	 * and assigns program globals through. Lowered by
	 * {@link LispMacroExpander#expandSetForCompile}: {@link #compileSetMirror} checks the
	 * name and writes the eval mirror, and a name with a compiled backing store writes
	 * that static field ({@link #compileGlobalStoreSet}, so compiled reads see the store)
	 * through the shared {@code %set-global} dispatch over the globals, or the same
	 * dispatch inline when the program lacks it. Like {@code setq}, the store assigns an
	 * already-active dynamic binding of a special. Forces {@code usesEval} in
	 * {@link JvmLispCompiler} like the rest of the symbol API.
	 */
	static void compileSet(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new IllegalArgumentException(LispNames.SET + " expects 2 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(LispMacroExpander.expandSetForCompile(cons, ctx.globals, ctx.specialVars,
				ctx.functions.containsKey(LispNames.SET_GLOBAL_RUNTIME)), ctx, className);
	}

	/**
	 * {@code (%set-mirror name value)} -- the checked half of {@code set}: constants
	 * (nil, t and keywords, by value or by computed name, and the empty name) and
	 * non-symbols signal; otherwise the eval mirror is written through {@code _store},
	 * which creates the binding when the name has none -- so {@code symbol-value} and
	 * {@code eval} see the store wherever it lands. Answers the stored value.
	 */
	static void compileSetMirror(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		int nameSlot = compileArgToTemp(parts.get(1), ctx, className);
		int valueSlot = compileArgToTemp(parts.get(2), ctx, className);
		// null (nil) -> throw
		ctx.body.aload(nameSlot);
		MethodCode.Label notNil = ctx.body.newLabel();
		ctx.body.ifnonnull(notNil);
		emitSetConstantThrow("NIL", ctx);
		ctx.body.labelBinding(notNil);
		// not a String (symbols are bare Strings, strings carry their quotes) -> throw
		ctx.body.aload(nameSlot).instanceOf(ctx.stringClass);
		MethodCode.Label isString = ctx.body.newLabel();
		ctx.body.ifne(isString);
		emitSetTypeThrow(nameSlot, ctx);
		ctx.body.labelBinding(isString);
		// T, NIL by computed name, keywords and the empty name are constants ->
		// throw. The empty name would otherwise sail through both probes below and
		// materialize a binding no read can spell.
		emitSetConstantNameThrow(nameSlot, "T", ctx);
		emitSetConstantNameThrow(nameSlot, "NIL", ctx);
		emitSetEmptyNameThrow(nameSlot, ctx);
		ctx.body.aload(nameSlot).checkcast(ctx.stringClass).iconst_0();
		ctx.body.invokevirtual(ctx.stringCharAt);
		JvmEmitHelper.emitIntConst(ctx, ':');
		MethodCode.Label notKeyword = ctx.body.newLabel();
		ctx.body.if_icmpne(notKeyword);
		emitSetConstantNameThrowDynamic(nameSlot, ctx);
		ctx.body.labelBinding(notKeyword);
		// the mirror: _store creates the binding when the name has none, and answers
		// the stored value
		ctx.body.aload(nameSlot).aload(valueSlot).aconst_null();
		ctx.body.invokestatic(java.util.Objects.requireNonNull(ctx.evalStoreRef));
	}

	/**
	 * {@code (%global-store-set NAME value)} -- the store of the literal global
	 * {@code NAME} that {@code setq} makes from a body where it is not lexical: a
	 * dynamically-bound special writes this thread's active {@code _d$} cell and falls to
	 * the {@code _g$} default only when none is active, any other global is a plain
	 * {@code putstatic} ({@link JvmSetqCompiler#emitGlobalStore}); answers the value.
	 */
	static void compileGlobalStoreSet(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		if (!ctx.globalFields.containsKey(name)) {
			throw new IllegalStateException("global " + name + " has no backing field for " + LispNames.SET);
		}
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		JvmSetqCompiler.emitGlobalStore(name, ctx);
	}

	/**
	 * {@code (%symbol-is x 'NAME)} as a raw int truth value: {@code "NAME".equals(x)} --
	 * a symbol is a bare String, so this is {@code equal} against the symbol without the
	 * structural walk. The name is not a designator the program spelled.
	 */
	static void emitSymbolIsTest(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		JvmEmitHelper.compileUnspelledLiteral(symbolIsName(cons), ctx);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		ctx.body.invokevirtual(ctx.objectEquals);
	}

	/** {@code (%symbol-is x 'NAME)} as a Lisp boolean. */
	static void compileSymbolIs(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		emitSymbolIsTest(cons, ctx, className);
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/** Whether {@code test} is a {@code (%symbol-is x 'NAME)} form. */
	static boolean isSymbolIs(LispVal test) {
		return test instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.SYMBOL_IS.equals(head.name());
	}

	private static String symbolIsName(LispCons cons) {
		LispCons quoted = (LispCons) cons.toList().get(2);
		return ((LispSymbol) ((LispCons) quoted.cdr()).car()).name();
	}

	// throw new RuntimeException("SET cannot set " + constant)
	private static void emitSetConstantThrow(String constant, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry ctor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral(LispNames.SET + " cannot set " + constant, ctx);
		ctx.body.invokespecial(ctor).athrow();
	}

	// throw new RuntimeException("SET cannot set " + name) for a computed constant name
	private static void emitSetConstantNameThrow(int nameSlot, String constant, JvmLispCompiler.Ctx ctx) {
		JvmEmitHelper.compileStringLiteral(constant, ctx);
		ctx.body.aload(nameSlot).invokevirtual(ctx.objectEquals);
		MethodCode.Label keep = ctx.body.newLabel();
		ctx.body.ifeq(keep);
		emitSetConstantThrow(constant, ctx);
		ctx.body.labelBinding(keep);
	}

	// throw new RuntimeException("SET cannot set " + name) for a keyword (the name is
	// on the stack as a String here)
	private static void emitSetConstantNameThrowDynamic(int nameSlot, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry ctor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry concat = ctx.cp.methodRef(ctx.stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral(LispNames.SET + " cannot set ", ctx);
		ctx.body.aload(nameSlot).checkcast(ctx.stringClass).invokevirtual(concat);
		ctx.body.invokespecial(ctor).athrow();
	}

	// throw new RuntimeException("SET cannot set ") for the empty name (the name is
	// on the stack as a String here)
	private static void emitSetEmptyNameThrow(int nameSlot, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry ctor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry isEmpty = ctx.cp.methodRef(ctx.stringClass, "isEmpty", "()Z");
		ctx.body.aload(nameSlot).checkcast(ctx.stringClass).invokevirtual(isEmpty);
		MethodCode.Label keep = ctx.body.newLabel();
		ctx.body.ifeq(keep);
		emitSetConstantThrow("", ctx);
		ctx.body.labelBinding(keep);
	}

	// throw new RuntimeException("SET expects a symbol, got " + value)
	private static void emitSetTypeThrow(int nameSlot, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry ctor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry valueOf = ctx.cp.methodRef(ctx.stringClass, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
		MethodRefEntry concat = ctx.cp.methodRef(ctx.stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral(LispNames.SET + " expects a symbol, got ", ctx);
		ctx.body.aload(nameSlot).invokestatic(valueOf).invokevirtual(concat);
		ctx.body.invokespecial(ctor).athrow();
	}

	/**
	 * {@code (%fenv-function name)} -- the name's {@code _fenv} binding value, or
	 * {@code throw new RuntimeException("The function X is undefined")} when no binding
	 * exists or {@code fmakunbound} cleared it (same text and catchability as the funcall
	 * dispatchers' miss arm). The compiled-function registry is deliberately NOT probed:
	 * the caller is the forwarder defun registered under the very name.
	 */
	static void compileFenvFunction(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.FENV_FUNCTION_INTERNAL);
		int nameSlot = compileArgToTemp(parts.get(1), ctx, className);
		ctx.body.aload(nameSlot).getstatic(evalField(ctx, className, "_fenv"));
		ctx.body.invokestatic(envLookupRef(ctx, className)).dup();
		MethodCode.Label noBinding = ctx.body.newLabel();
		ctx.body.ifnull(noBinding);
		ctx.body.checkcast(ctx.objectArrayClass).iconst_1().aaload().dup();
		MethodCode.Label cleared = ctx.body.newLabel();
		ctx.body.ifnull(cleared);
		MethodCode.Label end = ctx.body.newLabel();
		ctx.body.goto_(end);
		ctx.body.labelBinding(noBinding);
		ctx.body.labelBinding(cleared);
		ctx.body.pop();
		JvmFunctionFormCompiler.emitUndefinedFunctionThrow(nameSlot, ctx);
		ctx.body.labelBinding(end);
	}

	private static List<LispVal> requireArgs(LispCons cons, int count, String name) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != count + 1) {
			throw new UnsupportedOperationException(
					name + " expects " + count + " argument" + (count == 1 ? "" : "s") + ", got " + (parts.size() - 1));
		}
		return parts;
	}

	private static int compileArgToTemp(LispVal arg, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(arg, ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.body.astore(tempSlot);
		return tempSlot;
	}

	/**
	 * Emits a check for the self-bound symbols {@code t} and keywords. Control falls
	 * through into the caller's "self-bound" code when the value is {@code t} or a
	 * keyword; the caller binds the returned label at the "not self-bound" continuation.
	 */
	private static MethodCode.Label emitSelfBoundCheck(int tempSlot, JvmLispCompiler.Ctx ctx) {
		// "T".equals(value) -> self-bound
		JvmEmitHelper.compileStringLiteral("T", ctx);
		ctx.body.aload(tempSlot).invokevirtual(ctx.objectEquals);
		MethodCode.Label isT = ctx.body.newLabel();
		ctx.body.ifne(isT);
		// keyword: a String whose first char is ':'
		MethodCode.Label notSelfBound = ctx.body.newLabel();
		ctx.body.aload(tempSlot).instanceOf(ctx.stringClass).ifeq(notSelfBound);
		ctx.body.aload(tempSlot).checkcast(ctx.stringClass).iconst_0();
		ctx.body.invokevirtual(ctx.stringCharAt);
		JvmEmitHelper.emitIntConst(ctx, ':');
		ctx.body.if_icmpne(notSelfBound);
		// keyword falls through, t jumps here: both land in the self-bound code
		ctx.body.labelBinding(isT);
		return notSelfBound;
	}

	private static void emitGenvLookup(int tempSlot, JvmLispCompiler.Ctx ctx, String className) {
		ctx.body.aload(tempSlot).getstatic(evalField(ctx, className, "_genv"));
		ctx.body.invokestatic(envLookupRef(ctx, className));
	}

	// throw new RuntimeException("The variable " + name + " is unbound"): the landing
	// pad recovers the class and the name from the text (JvmHandlerCaseCompiler).
	private static void emitUnboundThrow(int tempSlot, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry ctor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry valueOf = ctx.cp.methodRef(ctx.stringClass, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral(ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX, ctx);
		ctx.body.aload(tempSlot).invokestatic(valueOf).invokevirtual(concat);
		JvmEmitHelper.compileStringLiteral(ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX, ctx);
		ctx.body.invokevirtual(concat).invokespecial(ctor).athrow();
	}

	/** Strips the surrounding quotes: {@code s.substring(1, s.length() - 1)}. */
	private static void emitStripQuotes(JvmLispCompiler.Ctx ctx) {
		MethodRefEntry length = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		MethodRefEntry substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;");
		ctx.body.checkcast(ctx.stringClass).dup().invokevirtual(length);
		ctx.body.iconst_1().isub().iconst_1().swap().invokevirtual(substring);
	}

	private static FieldRefEntry evalField(JvmLispCompiler.Ctx ctx, String className, String name) {
		return ctx.cp.fieldRef(ctx.cp.classEntry(className), name, "Ljava/lang/Object;");
	}

	private static MethodRefEntry envLookupRef(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.methodRef(ctx.cp.classEntry(className), "_envLookup",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
	}

}
