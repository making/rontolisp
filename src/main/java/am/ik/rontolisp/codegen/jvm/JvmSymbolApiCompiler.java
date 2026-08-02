package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;

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
		JvmPrincToStringCompiler.emitToString(parts.get(1), ctx.lispToDisplayString.index(), ctx, className);
	}

	/**
	 * string: the CL string-designator coercion. On the compiled path this is
	 * princ-to-string emission (like {@code symbol-name}); a string is returned
	 * unchanged, a symbol yields its name, a character a one-character string. The
	 * interpreter type- checks the argument; the compiled backend is lenient (the
	 * {@code symbol-name} precedent).
	 */
	static void compileString(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.STRING);
		// A keyword's package colon is a marker, not part of its name: (string :html) is
		// "html" (matches CL; cl-who relies on it to emit <html>, not <:html>).
		if (parts.get(1) instanceof LispSymbol sym && sym.isKeyword()) {
			JvmEmitHelper.compileStringLiteral(new LispString(sym.name().substring(1)).literal(), ctx);
			return;
		}
		JvmPrincToStringCompiler.emitToString(parts.get(1), ctx.lispToDisplayString.index(), ctx, className);
	}

	/** intern: strip the surrounding quotes from the runtime string. */
	static void compileIntern(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> full = cons.toList();
		if (full.size() == 3) {
			// (intern name pkg): the canonical-spelling lowering shared with the 2-arg
			// find-symbol (todo-229; an unknown package is a call-time signal).
			JvmExprCompiler.compileExpr(LispMacroExpander.expandInternInPackage(cons, ctx.packageTable), ctx,
					className);
			return;
		}
		List<LispVal> parts = requireArgs(cons, 1, LispNames.INTERN);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// A mutable character vector is a string here ((intern (make-string n)) after
		// the buffer is filled), so normalize before the quote strip casts to String.
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		emitStripQuotes(ctx);
	}

	/** make-symbol: {@code "#:".concat(content)} -- the gensym uninterned convention. */
	static void compileMakeSymbol(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.MAKE_SYMBOL);
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		JvmEmitHelper.compileStringLiteral("#:", ctx);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		emitStripQuotes(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
	}

	/**
	 * find-symbol: a LITERAL name folds at compile time against the compile-time view of
	 * the image (cl symbols, keywords, Pass-1 user defuns); a computed one lowers to
	 * {@code intern}, which is the lookup under the name-based symbol model.
	 */
	static void compileFindSymbol(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (cons.toList().size() == 3) {
			LispVal inPackage = LispMacroExpander.expandFindSymbolInPackage(cons, ctx.packageTable);
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
		String name = str.value();
		boolean known = PackageRegistry.isClSymbol(name) || (!name.isEmpty() && name.charAt(0) == ':')
				|| ctx.userDefunNames.contains(name);
		if (known) {
			JvmEmitHelper.compileStringLiteral(name, ctx);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
	}

	/** boundp: nil/t/keyword are self-bound, otherwise probe the {@code _genv} mirror. */
	static void compileBoundp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.BOUNDP);
		int tempSlot = compileArgToTemp(parts.get(1), ctx, className);
		// nil -> t
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		int ifNotNil = emitBranch(ctx, Opcode.IFNONNULL);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd1 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, ifNotNil, ctx.code.size());
		// t / keyword -> t
		int[] notSelfBound = emitSelfBoundCheck(tempSlot, ctx);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd2 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, notSelfBound[0], ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, notSelfBound[1], ctx.code.size());
		// _envLookup(name, _genv) != null -> t
		emitGenvLookup(tempSlot, ctx, className);
		int ifUnbound = emitBranch(ctx, Opcode.IFNULL);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd3 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, ifUnbound, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEnd1, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd2, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd3, ctx.code.size());
	}

	/**
	 * symbol-value: nil/t/keyword evaluate to themselves, otherwise read {@code _genv}.
	 */
	static void compileSymbolValue(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.SYMBOL_VALUE);
		int tempSlot = compileArgToTemp(parts.get(1), ctx, className);
		// nil -> nil
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		int ifNotNil = emitBranch(ctx, Opcode.IFNONNULL);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEnd1 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, ifNotNil, ctx.code.size());
		// t / keyword -> the symbol itself
		int[] notSelfBound = emitSelfBoundCheck(tempSlot, ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		int gotoEnd2 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, notSelfBound[0], ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, notSelfBound[1], ctx.code.size());
		// binding = _envLookup(name, _genv); null -> throw, else binding cdr
		emitGenvLookup(tempSlot, ctx, className);
		ctx.emit(Opcode.DUP);
		int ifUnbound = emitBranch(ctx, Opcode.IFNULL);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		int gotoEnd3 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, ifUnbound, ctx.code.size());
		ctx.emit(Opcode.POP);
		emitUnboundThrow(tempSlot, ctx);
		JvmEmitHelper.patchBranch(ctx, gotoEnd1, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd2, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd3, ctx.code.size());
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
			int[] foldEnd = ctx.usesFmakunbound ? emitTombstoneGuard(name, ctx, className) : null;
			if (bound) {
				JvmEmitHelper.compileTrue(ctx);
			}
			else {
				ctx.emit(Opcode.ACONST_NULL);
			}
			if (foldEnd != null) {
				JvmEmitHelper.patchBranch(ctx, foldEnd[0], ctx.code.size());
				JvmEmitHelper.patchBranch(ctx, foldEnd[1], ctx.code.size());
			}
			return;
		}
		int tempSlot = compileArgToTemp(parts.get(1), ctx, className);
		// nil -> nil
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		int ifNotNil = emitBranch(ctx, Opcode.IFNONNULL);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEnd1 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, ifNotNil, ctx.code.size());
		// binding = _envLookup(name, _fenv). A binding decides the answer on its own --
		// fmakunbound leaves a TOMBSTONE here (value cell null) that must SHADOW the
		// compiled registry probed below, or a retired name would answer t again.
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(envLookupRef(ctx, className).index());
		ctx.emit(Opcode.DUP);
		int fenvMiss = emitBranch(ctx, Opcode.IFNULL);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		int tombstone = emitBranch(ctx, Opcode.IFNULL);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd2 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, tombstone, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEnd4 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, fenvMiss, ctx.code.size());
		ctx.emit(Opcode.POP);
		// _lookup(name) != null -> t
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ConstantPool.MethodrefConstant lookupRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_lookup"),
						ctx.cp.addUtf8("(Ljava/lang/Object;)[Ljava/lang/Object;")));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(lookupRef.index());
		int registryMiss = emitBranch(ctx, Opcode.IFNULL);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd3 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, registryMiss, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEnd1, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd2, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd3, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd4, ctx.code.size());
	}

	/**
	 * Emits the tombstone half of {@code fboundp} for a literal name: when {@code _fenv}
	 * holds a binding for it, the answer is decided here (t when the value cell is set,
	 * nil when {@code fmakunbound} cleared it) and the caller's folded constant is
	 * skipped. Returns the GOTO positions the caller must patch to the end of the fold.
	 */
	private static int[] emitTombstoneGuard(String name, JvmLispCompiler.Ctx ctx, String className) {
		JvmEmitHelper.compileStringLiteral(name, ctx);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(envLookupRef(ctx, className).index());
		ctx.emit(Opcode.DUP);
		int noBinding = emitBranch(ctx, Opcode.IFNULL);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		int cleared = emitBranch(ctx, Opcode.IFNULL);
		JvmEmitHelper.compileTrue(ctx);
		int end = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, cleared, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		int end2 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, noBinding, ctx.code.size());
		ctx.emit(Opcode.POP);
		// The caller's folded constant follows; both tombstone answers jump past it.
		return new int[] { end, end2 };
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
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(envLookupRef(ctx, className).index());
		ctx.emit(Opcode.DUP);
		int create = emitBranch(ctx, Opcode.IFNULL);
		// existing binding: clear its value cell
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.AASTORE);
		int done = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, create, ctx.code.size());
		ctx.emit(Opcode.POP);
		// _fenv = new Object[]{new Object[]{name, null}, _fenv}
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		JvmEmitHelper.patchBranch(ctx, done, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
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
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(envLookupRef(ctx, className).index());
		ctx.emit(Opcode.DUP);
		int create = emitBranch(ctx, Opcode.IFNULL);
		// existing binding: overwrite its value cell
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valueSlot);
		ctx.emit(Opcode.AASTORE);
		int done = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, create, ctx.code.size());
		ctx.emit(Opcode.POP);
		// _fenv = new Object[]{new Object[]{name, value}, _fenv}
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valueSlot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		JvmEmitHelper.patchBranch(ctx, done, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valueSlot);
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
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(envLookupRef(ctx, className).index());
		ctx.emit(Opcode.DUP);
		int noBinding = emitBranch(ctx, Opcode.IFNULL);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.DUP);
		int cleared = emitBranch(ctx, Opcode.IFNULL);
		int end = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, noBinding, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, cleared, ctx.code.size());
		ctx.emit(Opcode.POP);
		emitUndefinedFunctionThrow(nameSlot, ctx);
		JvmEmitHelper.patchBranch(ctx, end, ctx.code.size());
	}

	// throw new RuntimeException("The function " + name + " is undefined") -- the
	// compile-expression twin of JvmEvalRuntimeBuilder.emitUndefinedFunctionThrow.
	private static void emitUndefinedFunctionThrow(int nameSlot, JvmLispCompiler.Ctx ctx) {
		ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant exCtor = ctx.cp.addMethodref(runtimeEx,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("(Ljava/lang/String;)V")));
		ConstantPool.MethodrefConstant concat = ctx.cp.addMethodref(ctx.stringClass, ctx.cp
			.addNameAndType(ctx.cp.addUtf8("concat"), ctx.cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtimeEx.index());
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.compileStringLiteral("The function ", ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat.index());
		JvmEmitHelper.compileStringLiteral(" is undefined", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat.index());
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(exCtor.index());
		ctx.emit(Opcode.ATHROW);
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
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		return tempSlot;
	}

	/** Emits a branch with a placeholder offset; returns the position for patchBranch. */
	private static int emitBranch(JvmLispCompiler.Ctx ctx, int opcode) {
		int pos = ctx.code.size();
		ctx.emit(opcode);
		ctx.emitU2(0);
		return pos;
	}

	/**
	 * Emits a check for the self-bound symbols {@code t} and keywords. Control falls
	 * through into the caller's "self-bound" code when the value is {@code t} or a
	 * keyword; the two returned branch positions must be patched by the caller to the
	 * "not self-bound" continuation.
	 */
	private static int[] emitSelfBoundCheck(int tempSlot, JvmLispCompiler.Ctx ctx) {
		// "T".equals(value) -> self-bound
		JvmEmitHelper.compileStringLiteral("T", ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.objectEquals.index());
		int isT = emitBranch(ctx, Opcode.IFNE);
		// keyword: a String whose first char is ':'
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.stringClass.index());
		int notString = emitBranch(ctx, Opcode.IFEQ);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.stringCharAt.index());
		JvmEmitHelper.emitIntConst(ctx, ':');
		int notKeyword = emitBranch(ctx, Opcode.IF_ICMPNE);
		// keyword falls through, t jumps here: both land in the self-bound code
		JvmEmitHelper.patchBranch(ctx, isT, ctx.code.size());
		return new int[] { notString, notKeyword };
	}

	private static void emitGenvLookup(int tempSlot, JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_genv").index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(envLookupRef(ctx, className).index());
	}

	private static void emitUnboundThrow(int tempSlot, JvmLispCompiler.Ctx ctx) {
		ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant ctor = ctx.cp.addMethodref(runtimeEx,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("(Ljava/lang/String;)V")));
		ConstantPool.MethodrefConstant valueOf = ctx.cp.addMethodref(ctx.stringClass, ctx.cp
			.addNameAndType(ctx.cp.addUtf8("valueOf"), ctx.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;")));
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtimeEx.index());
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.compileStringLiteral("The variable ", ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(valueOf.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		JvmEmitHelper.compileStringLiteral(" is unbound", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(ctor.index());
		ctx.emit(Opcode.ATHROW);
	}

	/** Strips the surrounding quotes: {@code s.substring(1, s.length() - 1)}. */
	private static void emitStripQuotes(JvmLispCompiler.Ctx ctx) {
		int length = JvmEmitHelper.stringMethod(ctx, "length", "()I").index();
		int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(length);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(substring);
	}

	private static ConstantPool.FieldrefConstant evalField(JvmLispCompiler.Ctx ctx, String className, String name) {
		return ctx.cp.addFieldref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8("Ljava/lang/Object;")));
	}

	private static ConstantPool.MethodrefConstant envLookupRef(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_envLookup"),
						ctx.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
	}

}
