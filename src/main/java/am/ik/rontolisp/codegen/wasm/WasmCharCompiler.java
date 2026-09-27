package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Compiles the character built-ins. A character is a {@code TYPE_CHAR} struct holding the
 * i32 code point. Because every compiler-allocated temporary local is a {@code (ref null
 * eq)}, i32 intermediates that must outlive a single stack expression are boxed as i31
 * refs and unboxed on use. Strings are UTF-8 encoded byte sequences (a Lisp index
 * {@code i} names the i-th Unicode CHARACTER, whose UTF-8 sequence starts at a byte
 * offset the {@code _str_char_at} runtime helper walks to and decodes).
 */
final class WasmCharCompiler {

	private WasmCharCompiler() {
	}

	/** {@code (char string index)} / {@code (schar string index)}. */
	static void compileChar(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// _str_char_ref reads a mutable character vector's ELEMENT (O(1), no rendered
		// string) and decodes an immutable string's UTF-8 through _str_char_at.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		// In EH mode an index that is no integer is CHAR's / SCHAR's type-error, and so
		// is a string that is no string: _str_char_ref lands under the register's
		// operator.
		WasmEmitHelper.emitIndexCheck(ctx);
		WasmEmitHelper.castI31GetS(ctx);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_STR_CHAR_REF);
		makeChar(ctx);
	}

	/**
	 * Compiles {@code (%check-string x 'op)}: {@code x}, which in EH mode lands as
	 * {@code op}'s {@code STRING} type-error when it is no string; outside EH mode no
	 * check is made (the store fails as it always did).
	 */
	static void compileCheckString(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileCheck(cons, ctx, OperandTypes.Kind.STRING, slot -> WasmStringpCompiler.emitStringpI32(ctx, slot));
	}

	/**
	 * Compiles {@code (%check-character x 'op)}: {@code x}, which in EH mode lands as
	 * {@code op}'s {@code CHARACTER} type-error when it is no character; outside EH mode
	 * no check is made (the store's cast traps).
	 */
	static void compileCheckCharacter(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileCheck(cons, ctx, OperandTypes.Kind.CHARACTER, slot -> {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		});
	}

	/**
	 * Compiles {@code (%operand-type-error x 'op 'kind)}: in EH mode {@code op}'s
	 * {@code kind} type-error over {@code x} (unnamed when the operator table has no row
	 * for it), outside it a trap -- the form never answers. In
	 * {@code %check-sequence-runtime}'s body the operator is a run-time token instead:
	 * the row id as an i31 ({@link #compileCheckSequence}), which goes into the operator
	 * register as it is.
	 */
	static void compileOperandTypeError(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispMacroExpander.OperandTypeErrorForm form = LispMacroExpander.OperandTypeErrorForm.of(cons);
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		LispVal operatorForm = form.operatorForm();
		if (!WasmEmitHelper.checksConsFields(ctx)) {
			if (operatorForm != null) {
				WasmExprCompiler.compileExpr(operatorForm, ctx);
				ctx.writer.write(Instruction.DROP);
			}
			ctx.writer.write(Instruction.UNREACHABLE);
			return;
		}
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		OperandTypes.Kind kind = OperandTypes.Kind.named(form.kind());
		if (operatorForm != null) {
			WasmExprCompiler.compileExpr(operatorForm, ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(ctx.operandOpGlobalIndex);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			WasmOperandTypes.emitLanding(ctx.writer, kind);
			return;
		}
		WasmOperandTypes.withOperator(ctx, form.operator(), () -> WasmOperandTypes.emitTypeError(ctx, slot, kind));
	}

	/**
	 * Compiles {@code (%check-sequence x 'op)}: a call to the module's shared
	 * {@code %check-sequence-runtime} with the operator's token -- its row id in the
	 * operator table, 0 for none (and outside EH mode, where no table exists) -- and the
	 * inline check when the module carries no such defun.
	 */
	static void compileCheckSequence(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.functions.containsKey(LispNames.CHECK_SEQUENCE_RUNTIME)) {
			WasmExprCompiler.compileExpr(LispMacroExpander.checkSequenceInline(cons), ctx);
			return;
		}
		int id = WasmOperandTypes.operatorId(ctx, LispMacroExpander.checkSequenceOperator(cons));
		LispVal call = new LispCons(new LispSymbol(LispNames.CHECK_SEQUENCE_RUNTIME),
				new LispCons(cons.toList().get(1), new LispCons(new LispInteger(id), LispNil.INSTANCE)));
		WasmExprCompiler.compileExpr(call, ctx);
	}

	/**
	 * Compiles a check form's operand and, in EH mode, lands it as the form's operator's
	 * {@code kind} type-error when {@code test} (an i32 over the operand's local) fails.
	 */
	private static void compileCheck(LispCons cons, WasmLispCompiler.Ctx ctx, OperandTypes.Kind kind,
			java.util.function.IntConsumer test) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		if (!WasmEmitHelper.checksConsFields(ctx)) {
			return;
		}
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		test.accept(slot);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		WasmOperandTypes.withOperator(ctx, LispMacroExpander.checkOperator(cons),
				() -> WasmOperandTypes.emitTypeError(ctx, slot, kind));
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	/**
	 * Compiles {@code (%check-index x 'op)}: {@code x}, checked in EH mode to be an
	 * integer under {@code op} ({@link WasmEmitHelper#emitIndexCheck}) -- unnamed when
	 * {@code op} is nil.
	 */
	static void compileCheckIndex(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		WasmOperandTypes.withOperator(ctx, LispMacroExpander.checkOperator(cons),
				() -> WasmEmitHelper.emitIndexCheck(ctx));
	}

	/** {@code (char-code ch)}. */
	static void compileCharCode(LispCons cons, WasmLispCompiler.Ctx ctx) {
		pushCode(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/** {@code (code-char n)}. */
	static void compileCodeChar(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		makeChar(ctx);
	}

	/** {@code (characterp x)}. */
	static void compileCharacterp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/** {@code (char-upcase ch)}. Full-Unicode fold via {@code _char_upcase}. */
	static void compileUpcase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileCaseFold(cons, ctx, WasmLispCompiler.FUNC_CHAR_UPCASE);
	}

	/** {@code (char-downcase ch)}. Full-Unicode fold via {@code _char_downcase}. */
	static void compileDowncase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileCaseFold(cons, ctx, WasmLispCompiler.FUNC_CHAR_DOWNCASE);
	}

	// Pushes the code point, calls the case-fold helper (identity for a non-letter code
	// point) and boxes the returned i32 back into a TYPE_CHAR struct.
	// Character.toUpperCase
	// / toLowerCase always return a SINGLE code point, so the whole char fold stays
	// inside
	// TYPE_CHAR without allocating a string.
	private static void compileCaseFold(LispCons cons, WasmLispCompiler.Ctx ctx, int funcIndex) {
		pushCode(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(funcIndex);
		makeChar(ctx);
	}

	/** {@code (alpha-char-p ch)} (ASCII letters). */
	static void compileAlphaCharP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		int t = ctx.allocTemp();
		pushCode(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(t);
		inRange(ctx, t, 'A', 'Z');
		inRange(ctx, t, 'a', 'z');
		ctx.writer.write(Instruction.I32_OR);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/** {@code (digit-char-p ch [radix])}. */
	static void compileDigitCharP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int c = ctx.allocTemp();
		int r = ctx.allocTemp();
		int d = ctx.allocTemp();
		pushCode(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(c);
		if (args.size() > 2) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
		}
		else {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(10);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(r);
		// d = weight of c (digit / letter), or -1
		inRange(ctx, c, '0', '9');
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		getI32(ctx, c);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128('0');
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.ELSE);
		emitLetterWeight(ctx, c, 'A', 'Z');
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		getI32(ctx, c);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128('A' - 10);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.ELSE);
		emitLetterWeight(ctx, c, 'a', 'z');
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		getI32(ctx, c);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128('a' - 10);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(-1);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(d);
		// (d >= 0 && d < radix) ? i31(d) : nil
		getI32(ctx, d);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_GE_S);
		getI32(ctx, d);
		getI32(ctx, r);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getI32(ctx, d);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	/** {@code (char= ...)}. */
	static void compileEq(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_EQ, false, false);
	}

	/** {@code (char< ...)}. */
	static void compileLt(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_LT_S, false, false);
	}

	/** {@code (char<= ...)}. */
	static void compileLe(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_LE_S, false, false);
	}

	/** {@code (char> ...)}. */
	static void compileGt(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_GT_S, false, false);
	}

	/** {@code (char>= ...)}. */
	static void compileGe(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_GE_S, false, false);
	}

	/** {@code (char/= ...)}: every PAIR of arguments distinct, not just adjacent ones. */
	static void compileNe(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_NE, false, true);
	}

	/** {@code (char-equal ...)}: {@code char=} over the downcased code points. */
	static void compileEqual(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_EQ, true, false);
	}

	/**
	 * A two-operand character comparison as a raw i32 (0 = false, non-0 = true): both
	 * code points pushed, one compare -- no temp, no i31 box. The value-position compile
	 * boxes this into t/nil; {@link WasmConditionCompiler} tests it directly, which is
	 * why the operands are checked under the comparison's own operator here rather than
	 * the innermost form's.
	 * @param cons the comparison form, exactly two operands
	 * @param ctx the function context
	 * @param cmpOpcode the i32 comparison
	 */
	static void emitPairCompareI32(LispCons cons, WasmLispCompiler.Ctx ctx, int cmpOpcode) {
		List<LispVal> args = cons.toList();
		WasmOperandTypes.withOperator(ctx, ((LispSymbol) cons.car()).name(), () -> {
			pushCheckedCode(args.get(1), ctx, false);
			pushCheckedCode(args.get(2), ctx, false);
		});
		ctx.writer.write(cmpOpcode);
	}

	/**
	 * The i32 comparison a two-operand case-sensitive character comparison names, or -1.
	 * @param name the operator name
	 * @return the opcode, or -1 for any other name
	 */
	static int pairCompareOpcode(String name) {
		return switch (name) {
			case am.ik.rontolisp.LispNames.CHAR_EQ -> Instruction.I32_EQ;
			case am.ik.rontolisp.LispNames.CHAR_LT -> Instruction.I32_LT_S;
			case am.ik.rontolisp.LispNames.CHAR_LE -> Instruction.I32_LE_S;
			case am.ik.rontolisp.LispNames.CHAR_GT -> Instruction.I32_GT_S;
			case am.ik.rontolisp.LispNames.CHAR_GE -> Instruction.I32_GE_S;
			case am.ik.rontolisp.LispNames.CHAR_NE -> Instruction.I32_NE;
			default -> -1;
		};
	}

	// A variadic character comparison: true only when every compared pair -- adjacent
	// ones, or all of them with allPairs -- satisfies cmpOpcode. Every argument is
	// evaluated and checked before any pair is compared, the lone argument of a
	// one-argument call included (.kb/error-handling.md, "One argument is still
	// checked").
	private static void compileChain(LispCons cons, WasmLispCompiler.Ctx ctx, int cmpOpcode, boolean fold,
			boolean allPairs) {
		List<LispVal> args = cons.toList();
		int n = args.size() - 1;
		if (n < 1) {
			throw new IllegalArgumentException(((LispSymbol) cons.car()).name() + " expects at least one argument");
		}
		if (n == 1) {
			pushCheckedCode(args.get(1), ctx, fold);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			WasmEmitHelper.emitBoolFromI32(ctx);
			return;
		}
		if (n == 2) {
			// The pair, which is nearly every site: both code points on the stack, one
			// compare (the chain below spent eqref temps and an i31 box per operand on
			// it -- 228 sites on the hello-clack Worker).
			pushCheckedCode(args.get(1), ctx, fold);
			pushCheckedCode(args.get(2), ctx, fold);
			ctx.writer.write(cmpOpcode);
			WasmEmitHelper.emitBoolFromI32(ctx);
			return;
		}
		int[] codes = new int[n];
		for (int i = 0; i < n; i++) {
			pushCheckedCode(args.get(i + 1), ctx, fold);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			codes[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(codes[i]);
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		for (int i = 0; i + 1 < n; i++) {
			for (int j = i + 1; j < (allPairs ? n : i + 2); j++) {
				getI32(ctx, codes[i]);
				getI32(ctx, codes[j]);
				ctx.writer.write(cmpOpcode);
				ctx.writer.write(Instruction.I32_AND);
			}
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// Pushes the i32 code point of a comparison's argument -- downcased with fold -- a
	// literal's as the constant it is. In EH mode any other goes through _chr_code under
	// the innermost named operator's id, so a non-character is that operator's CHARACTER
	// type-error rather than the cast's trap; outside it the cast still traps.
	private static void pushCheckedCode(LispVal arg, WasmLispCompiler.Ctx ctx, boolean fold) {
		if (arg instanceof am.ik.rontolisp.LispChar c) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fold ? Character.toLowerCase(c.codePoint()) : c.codePoint());
			return;
		}
		WasmExprCompiler.compileExpr(arg, ctx);
		if (WasmEmitHelper.checksConsFields(ctx)) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmOperandTypes.operatorId(ctx));
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_CHR_CODE);
		}
		else {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
			ctx.writer.writeUnsignedLeb128(0);
		}
		if (fold) {
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_CHAR_DOWNCASE);
		}
	}

	// Pushes the i32 code point of the character produced by the argument expression --
	// a literal's as the constant it is, not through a char struct.
	private static void pushCode(LispVal arg, WasmLispCompiler.Ctx ctx) {
		if (arg instanceof am.ik.rontolisp.LispChar c) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(c.codePoint());
			return;
		}
		WasmExprCompiler.compileExpr(arg, ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.writeUnsignedLeb128(0);
	}

	/**
	 * Builds {@code _chr_code(value, id) -> i32}: a character answers its code point at
	 * once; anything else sets the operator register to {@code id} and lands as that
	 * operator's {@code CHARACTER} type-error ({@code _type_err}, which never returns):
	 * {@code local.get 0; block (eqref -> eqref) br_on_cast_fail 0 eqref $char;
	 * struct.get $char 0; return end; local.get 1; global.set $op; <landing>}. A bare
	 * {@code unreachable} outside EH mode, where nothing calls it.
	 * @param operatorGlobal the operator register, or -1 outside EH mode
	 * @return the function body
	 */
	static byte[] buildCodeCheckBody(int operatorGlobal) {
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals: the value and the operator id
		if (operatorGlobal < 0) {
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.BLOCK);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CALLABLE_BASE);
		w.write(Instruction.GC_PREFIX, Instruction.BR_ON_CAST_FAIL);
		w.write(0x01); // the operand nullable, the cast not
		w.writeUnsignedLeb128(0);
		w.writeHeapType(Type.EQ.code());
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(operatorGlobal);
		WasmOperandTypes.emitLanding(w, OperandTypes.Kind.CHARACTER);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Boxes the i32 on the stack into a TYPE_CHAR struct.
	static void makeChar(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
	}

	// Reads an i31-boxed i32 temp back onto the stack as a raw i32.
	private static void getI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	// Pushes 1 when the i31-boxed code in slot is within [lo, hi], else 0.
	private static void inRange(WasmLispCompiler.Ctx ctx, int slot, char lo, char hi) {
		getI32(ctx, slot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(lo);
		ctx.writer.write(Instruction.I32_GE_S);
		getI32(ctx, slot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(hi);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.I32_AND);
	}

	// Pushes 1 when the i31-boxed code in slot is a letter within [lo, hi], else 0
	// (a synonym for inRange, named for readability at the digit-weight call sites).
	private static void emitLetterWeight(WasmLispCompiler.Ctx ctx, int slot, char lo, char hi) {
		inRange(ctx, slot, lo, hi);
	}

}
