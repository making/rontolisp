package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the character built-ins. A CHARACTER on the JVM compile path is a length-1
 * {@code int[]} whose sole element is the Unicode code point -- a wider representation
 * than the old {@code java.lang.Character} 16-bit box, so a supplementary code point like
 * {@code #\U+1F600} carries its full 21-bit value through {@code code-char},
 * {@code char-code}, {@code char-upcase}/{@code char-downcase}, {@code (string ch)} and
 * every printer. The type discriminator is {@code instanceof int[]}: functions
 * ({@code Object[]}), ratios ({@code BigInteger[]}), packed float arrays
 * ({@code double[]} / {@code float[]}) all pick different array classes.
 *
 * <p>
 * Strings are UTF-16 buffers with surrounding double quotes; a Lisp CHARACTER index is a
 * CODE POINT index, translated by {@link JvmStringIndexRuntimeBuilder}'s {@code _cpoff},
 * so the same astral glyph reads back as one indexed character on {@code (char s i)} /
 * {@code (aref s i)} / {@code (subseq s a b)}.
 */
final class JvmCharCompiler {

	private JvmCharCompiler() {
	}

	/**
	 * {@code (char string index)} / {@code (schar string index)}: the code point at
	 * index, via ONE {@code _charRef} call ({@link JvmStringIndexRuntimeBuilder}). A
	 * mutable character vector reads its element there (an O(1) {@code _rmGet}, never a
	 * rendered string); an immutable string translates the CHARACTER position to a UTF-16
	 * code-unit offset via {@code _cpoff} so a supplementary code point counts as one
	 * indexed character.
	 */
	static void compileChar(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		// An index that is no integer is CHAR's / SCHAR's type-error, named by the
		// operator's wrapper (JvmOperandTypeRuntime).
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.CK_IDX).index());
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		// A string that is no string is CHAR's / SCHAR's type-error too: _charRef throws
		// the unnamed report, the operator's wrapper names it.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.wrapForOperator(JvmStringIndexRuntimeBuilder.CHARREF_METHOD,
				JvmStringIndexRuntimeBuilder.CHARREF_DESC, JvmEmitHelper.selfMethod(ctx, className,
						JvmStringIndexRuntimeBuilder.CHARREF_METHOD, JvmStringIndexRuntimeBuilder.CHARREF_DESC))
			.index());
		JvmEmitHelper.boxCodePoint(ctx);
	}

	/**
	 * Compiles {@code (%check-string x 'op)}: {@code x}, left on the stack when it is a
	 * string (the shared {@code _pStringp} test) and otherwise {@code op}'s
	 * {@code STRING} type-error ({@link #emitSiteTypeError}).
	 */
	static void compileCheckString(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.emitSharedCall(ctx, className, "_pStringp", 1,
				helper -> JvmStringpCompiler.emitStringpCheck(helper, 0));
		emitSiteTypeError(cons, ctx, className, Opcode.IFNONNULL, OperandTypes.Kind.STRING);
	}

	/**
	 * Compiles {@code (%check-character x 'op)}: {@code x}, left on the stack when it is
	 * a character ({@code int[]}) and otherwise {@code op}'s {@code CHARACTER} type-error
	 * ({@link #emitSiteTypeError}).
	 */
	static void compileCheckCharacter(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.charArrayClass(ctx).index());
		emitSiteTypeError(cons, ctx, className, Opcode.IFNE, OperandTypes.Kind.CHARACTER);
	}

	/**
	 * Compiles {@code (%operand-type-error x 'op 'kind)}: {@code kind}'s report of
	 * {@code x}, {@code _teRaw} named by {@code _opTypeErr} after {@code op} (unnamed
	 * when it is no named operator), thrown here. Nothing follows the throw: the form
	 * never answers, as {@code %error} never does. In {@code %check-sequence-runtime}'s
	 * body the operator is a run-time token instead, the reported name as a string or nil
	 * ({@link #compileCheckSequence}), and the rename happens only when it is a name.
	 */
	static void compileOperandTypeError(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispMacroExpander.OperandTypeErrorForm form = LispMacroExpander.OperandTypeErrorForm.of(cons);
		LispVal operatorForm = form.operatorForm();
		int tokenSlot = -1;
		if (operatorForm != null) {
			JvmExprCompiler.compileExpr(operatorForm, ctx, className);
			tokenSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tokenSlot);
		}
		JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
		JvmEmitHelper.compileUnspelledLiteral(OperandTypes.Kind.named(form.kind()).typeName(), ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper
			.selfMethod(ctx, className, JvmOperandTypeRuntime.TE_RAW, JvmOperandTypeRuntime.TE_RAW_DESC)
			.index());
		if (tokenSlot >= 0) {
			// nil: the raw report, thrown as it is; a name: renamed, then thrown.
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tokenSlot);
			int named = ctx.code.size();
			ctx.emit(Opcode.IFNONNULL);
			ctx.emitU2(0);
			ctx.emit(Opcode.ATHROW);
			JvmEmitHelper.patchBranch(ctx, named, ctx.code.size());
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tokenSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.stringClass.index());
			emitOpTypeErr(ctx, className, OperandTypes.FUNNEL_TYPE);
			ctx.emit(Opcode.ATHROW);
			return;
		}
		String operator = OperandTypes.reportedOperator(form.operator());
		if (operator != null) {
			JvmEmitHelper.compileUnspelledLiteral(operator, ctx);
			// The report names the operator's own type, as its wrappers do: the
			// funnel-typed rename reads a NUMBER kind as REAL, so a NUMBER operator
			// ((+ x)'s one-argument check) passes its type, and every other keeps the
			// funnel-typed rename, whose kind is the type it names.
			boolean numberTyped = OperandTypes.Kind.NUMBER.name().equals(OperandTypes.operatorType(operator));
			emitOpTypeErr(ctx, className, numberTyped ? OperandTypes.Kind.NUMBER.name() : OperandTypes.FUNNEL_TYPE);
		}
		ctx.emit(Opcode.ATHROW);
	}

	// With the raw report and the operator's name on the stack: the rename under the
	// operator's type (FUNNEL_TYPE: the report's own kind).
	private static void emitOpTypeErr(JvmLispCompiler.Ctx ctx, String className, String type) {
		JvmEmitHelper.compileUnspelledLiteral(type, ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper
			.selfMethod(ctx, className, JvmOperandTypeRuntime.OP_TYPE_ERR, JvmOperandTypeRuntime.OP_TYPE_ERR_DESC)
			.index());
	}

	/**
	 * Compiles {@code (%check-sequence x 'op)}: a direct call of the program's shared
	 * {@code %check-sequence-runtime} with the operator's token -- the name the report
	 * gives it as an unspelled string, or null -- and the inline check when the program
	 * carries no such defun. The token is no quoted symbol: a quoted function name keeps
	 * that function's #' wrapper from the shaker.
	 */
	static void compileCheckSequence(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.FunctionInfo helper = ctx.functions.get(LispNames.CHECK_SEQUENCE_RUNTIME);
		if (helper == null) {
			JvmExprCompiler.compileExpr(LispMacroExpander.checkSequenceInline(cons), ctx, className);
			return;
		}
		String reported = OperandTypes.reportedOperator(LispMacroExpander.checkSequenceOperator(cons));
		LispVal value = cons.toList().get(1);
		JvmPhysicalArgs.emit(ctx, className, helper,
				List.of(() -> JvmExprCompiler.compileExpr(value, ctx, className), () -> {
					if (reported == null) {
						ctx.emit(Opcode.ACONST_NULL);
					}
					else {
						JvmEmitHelper.compileUnspelledLiteral(reported, ctx);
					}
				}));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(helper.methodref().index());
	}

	/**
	 * Emits a check's miss over the value under its test's result: {@code ifPass} skips
	 * it, else {@code kind}'s report of the value -- {@code _teRaw} named by
	 * {@code _opTypeErr} after the check's operator, unnamed without one -- thrown here.
	 * Only a string store checks this way, so the site carries the throw rather than a
	 * wrapper.
	 */
	private static void emitSiteTypeError(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int ifPass,
			OperandTypes.Kind kind) {
		int pass = ctx.code.size();
		ctx.emit(ifPass);
		ctx.emitU2(0);
		JvmEmitHelper.compileUnspelledLiteral(kind.name(), ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper
			.selfMethod(ctx, className, JvmOperandTypeRuntime.TE_RAW, JvmOperandTypeRuntime.TE_RAW_DESC)
			.index());
		String operator = LispMacroExpander.checkOperator(cons);
		if (operator != null) {
			JvmEmitHelper.compileUnspelledLiteral(operator, ctx);
			JvmEmitHelper.compileUnspelledLiteral(OperandTypes.FUNNEL_TYPE, ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(JvmEmitHelper
				.selfMethod(ctx, className, JvmOperandTypeRuntime.OP_TYPE_ERR, JvmOperandTypeRuntime.OP_TYPE_ERR_DESC)
				.index());
		}
		ctx.emit(Opcode.ATHROW);
		JvmEmitHelper.patchBranch(ctx, pass, ctx.code.size());
	}

	/**
	 * Compiles {@code (%check-index x 'op)}: {@code x} through {@code _ckIdx} under
	 * {@code op}'s wrapper -- unnamed when {@code op} is nil.
	 */
	static void compileCheckIndex(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
		@Nullable String outer = ctx.operator;
		ctx.operator = LispMacroExpander.checkOperator(cons);
		try {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.CK_IDX).index());
		}
		finally {
			ctx.operator = outer;
		}
	}

	/** {@code (char-code ch)}: the code point as an integer. */
	static void compileCharCode(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		pushCheckedCode(cons.toList().get(1), ctx, className, false);
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	/**
	 * {@code (char-int ch)}: the same code point {@code char-code} answers -- with no
	 * implementation-defined attributes beyond it, char-int has nothing else to encode.
	 */
	static void compileCharInt(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		pushCheckedCode(cons.toList().get(1), ctx, className, false);
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	/** {@code (code-char n)}: the character with the given code point. */
	static void compileCodeChar(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		JvmEmitHelper.boxCodePoint(ctx);
	}

	/**
	 * {@code (char-upcase ch)}. Full-Unicode fold via {@code Character.toUpperCase(int)}.
	 */
	static void compileUpcase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseFold(cons, ctx, className, "toUpperCase");
	}

	/**
	 * {@code (char-downcase ch)}. Full-Unicode fold via
	 * {@code Character.toLowerCase(int)}.
	 */
	static void compileDowncase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseFold(cons, ctx, className, "toLowerCase");
	}

	private static void compileCaseFold(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String method) {
		pushCheckedCode(cons.toList().get(1), ctx, className, false);
		// Character.toUpperCase(int)/toLowerCase(int) take a code point and return a code
		// point; a mapping that would expand to multiple code units lives on the String
		// overload, so this is the right level for a single-character fold.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, method, "(I)I").index());
		JvmEmitHelper.boxCodePoint(ctx);
	}

	/** {@code (characterp x)}: {@code instanceof int[]}. */
	static void compileCharacterp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.charArrayClass(ctx).index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/** {@code (alpha-char-p ch)}: {@code Character.isLetter(int)} on the code point. */
	static void compileAlphaCharP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		pushCheckedCode(cons.toList().get(1), ctx, className, false);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "isLetter", "(I)Z").index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/**
	 * {@code (lower-case-p ch)}: whether upcasing changes the code point, as the
	 * interpreter's built-in answers.
	 */
	static void compileLowerCaseP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseTest(cons, ctx, className, "toUpperCase");
	}

	/**
	 * {@code (upper-case-p ch)}: whether downcasing changes the code point, as the
	 * interpreter's built-in answers.
	 */
	static void compileUpperCaseP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseTest(cons, ctx, className, "toLowerCase");
	}

	// The checked code point against its fold through method: t when they differ.
	private static void compileCaseTest(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String method) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new IllegalArgumentException(((LispSymbol) cons.car()).name() + " expects exactly one argument");
		}
		pushCheckedCode(args.get(1), ctx, className, false);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, method, "(I)I").index());
		ctx.emit(Opcode.ISUB);
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/** {@code (digit-char-p ch [radix])}: the digit weight, or nil. */
	static void compileDigitCharP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		pushCheckedCode(args.get(1), ctx, className, false);
		if (args.size() > 2) {
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			if (!(args.get(2) instanceof LispInteger)) {
				// A radix that is no integer is DIGIT-CHAR-P's INTEGER type-error.
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.CK_IDX).index());
			}
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.L2I);
		}
		else {
			JvmEmitHelper.emitIntConst(ctx, 10);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "digit", "(II)I").index());
		// weight on stack: if weight < 0 return nil, else Long.valueOf(weight)
		ctx.emit(Opcode.DUP);
		int ifNotDigit = ctx.code.size();
		ctx.emit(Opcode.IFLT);
		ctx.emitU2(0);
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotDigit, ctx.code.size());
		ctx.emit(Opcode.POP); // discard the -1
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

	/** {@code (char= ...)} variadic equality. */
	static void compileEq(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPNE, false, false);
	}

	/** {@code (char< ...)} variadic strictly-increasing comparison. */
	static void compileLt(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPGE, false, false);
	}

	/** {@code (char<= ...)} variadic non-decreasing comparison. */
	static void compileLe(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPGT, false, false);
	}

	/** {@code (char> ...)} variadic strictly-decreasing comparison. */
	static void compileGt(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPLE, false, false);
	}

	/** {@code (char>= ...)} variadic non-increasing comparison. */
	static void compileGe(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPLT, false, false);
	}

	/** {@code (char/= ...)}: every PAIR of arguments distinct, not just adjacent ones. */
	static void compileNe(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPEQ, false, true);
	}

	/** {@code (char-equal ...)}: {@code char=} over the downcased code points. */
	static void compileEqual(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPNE, true, false);
	}

	// Emits a variadic character comparison: true only when every compared pair --
	// adjacent ones, or all of them with allPairs -- satisfies the relation. failOpcode
	// is the branch to the nil result when a pair fails it. Every argument is evaluated
	// and checked before any pair is compared, the lone argument of a one-argument call
	// included, so a non-character is the comparison's CHARACTER type-error wherever it
	// stands (.kb/error-handling.md, "One argument is still checked").
	private static void compileChain(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int failOpcode,
			boolean fold, boolean allPairs) {
		List<LispVal> args = cons.toList();
		int n = args.size() - 1;
		if (n < 1) {
			throw new IllegalArgumentException(((LispSymbol) cons.car()).name() + " expects at least one argument");
		}
		if (n == 1) {
			pushCheckedCode(args.get(1), ctx, className, fold);
			ctx.emit(Opcode.POP);
			JvmEmitHelper.compileTrue(ctx);
			return;
		}
		List<Integer> failBranches = new ArrayList<>();
		if (n == 2) {
			pushCheckedCode(args.get(1), ctx, className, fold);
			pushCheckedCode(args.get(2), ctx, className, fold);
			failBranches.add(ctx.code.size());
			ctx.emit(failOpcode);
			ctx.emitU2(0);
		}
		else {
			int[] codes = new int[n];
			for (int i = 0; i < n; i++) {
				pushCheckedCode(args.get(i + 1), ctx, className, fold);
				codes[i] = ctx.allocTemp();
				ctx.emit(Opcode.ISTORE);
				ctx.emit(codes[i]);
			}
			for (int i = 0; i + 1 < n; i++) {
				for (int j = i + 1; j < (allPairs ? n : i + 2); j++) {
					ctx.emit(Opcode.ILOAD);
					ctx.emit(codes[i]);
					ctx.emit(Opcode.ILOAD);
					ctx.emit(codes[j]);
					failBranches.add(ctx.code.size());
					ctx.emit(failOpcode);
					ctx.emitU2(0);
				}
			}
		}
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		for (int pos : failBranches) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

	// Pushes the int code point of a character built-in's argument -- downcased with
	// fold -- a literal's as the constant it is; any other through _ckChr, under the
	// operator's wrapper, so a non-character is its named type-error rather than a
	// failed cast.
	private static void pushCheckedCode(LispVal arg, JvmLispCompiler.Ctx ctx, String className, boolean fold) {
		if (arg instanceof LispChar c) {
			JvmEmitHelper.emitIntConst(ctx, fold ? Character.toLowerCase(c.codePoint()) : c.codePoint());
			return;
		}
		JvmExprCompiler.compileExpr(arg, ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.CK_CHR).index());
		JvmEmitHelper.unboxCodePoint(ctx);
		if (fold) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "toLowerCase", "(I)I").index());
		}
	}

}
