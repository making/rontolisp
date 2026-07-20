package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
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
			JvmEmitHelper.compileStringLiteral(new LispString(sym.name().substring(1)).print(), ctx);
			return;
		}
		JvmPrincToStringCompiler.emitToString(parts.get(1), ctx.lispToDisplayString.index(), ctx, className);
	}

	/** intern: strip the surrounding quotes from the runtime string. */
	static void compileIntern(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> full = cons.toList();
		if (full.size() == 3) {
			// (intern name :keyword) -> (intern (concatenate 'string ":" name)): keeps
			// the
			// keyword lowering backend-neutral. Any other package argument is
			// unsupported.
			if (LispMacroExpander.isKeywordPackageDesignator(full.get(2))) {
				JvmExprCompiler.compileExpr(LispMacroExpander.internKeywordForm(full.get(1)), ctx, className);
				return;
			}
			throw new UnsupportedOperationException(
					LispNames.INTERN + " with a non-keyword package argument is not supported");
		}
		List<LispVal> parts = requireArgs(cons, 1, LispNames.INTERN);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		emitStripQuotes(ctx);
	}

	/** make-symbol: {@code "#:".concat(content)} -- the gensym uninterned convention. */
	static void compileMakeSymbol(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.MAKE_SYMBOL);
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		JvmEmitHelper.compileStringLiteral("#:", ctx);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		emitStripQuotes(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
	}

	/**
	 * find-symbol: folded at compile time against the compile-time view of the image (cl
	 * symbols, keywords, Pass-1 user defuns). The argument must be a literal string.
	 */
	static void compileFindSymbol(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (cons.toList().size() == 3) {
			throw new UnsupportedOperationException(
					LispNames.FIND_SYMBOL + " with a package argument is not supported");
		}
		List<LispVal> parts = requireArgs(cons, 1, LispNames.FIND_SYMBOL);
		if (!(parts.get(1) instanceof LispString str)) {
			throw new UnsupportedOperationException(
					"Cannot compile: " + cons.print() + " (find-symbol requires a literal string in compiled mode)");
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
	 */
	static void compileFboundp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = requireArgs(cons, 1, LispNames.FBOUNDP);
		if (parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && ((LispCons) quoteForm.cdr()).car() instanceof LispSymbol sym) {
			String name = sym.name();
			boolean bound = PackageRegistry.specialOperatorNames().contains(name)
					|| PackageRegistry.clFunctionNames().contains(name) || LispMacroExpander.isCarCdrComposition(name)
					|| ctx.userDefunNames.contains(name) || ctx.functions.containsKey(name);
			if (bound) {
				JvmEmitHelper.compileTrue(ctx);
			}
			else {
				ctx.emit(Opcode.ACONST_NULL);
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
		// _envLookup(name, _fenv) != null -> t
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(evalField(ctx, className, "_fenv").index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(envLookupRef(ctx, className).index());
		int fenvMiss = emitBranch(ctx, Opcode.IFNULL);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd2 = emitBranch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, fenvMiss, ctx.code.size());
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
