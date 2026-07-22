package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the array built-ins ({@code make-array}, {@code aref}, {@code %aset}, and the
 * fill-pointer surface). Each pushes its arguments and calls the matching static runtime
 * helper emitted by {@link JvmArrayRuntimeBuilder}. {@code make-array} resolves the
 * {@code :initial-element}/{@code :fill-pointer}/{@code :adjustable} keywords at compile
 * time (their value expressions are compiled and evaluated at runtime);
 * {@code aref}/{@code %aset} pick a helper by the number of subscripts: ranks 1 and 2
 * call dedicated fast helpers, higher ranks package the subscripts into an
 * {@code Object[]} for the generic {@code _arefN}/{@code _asetN}.
 */
final class JvmArrayCompiler {

	private JvmArrayCompiler() {
	}

	// The packed float-array dispatch helper name when the program uses packed arrays,
	// otherwise the general array helper. Both share the same descriptor, so only the
	// invoked method name changes; the default build (no packed arrays) is
	// byte-identical.
	private static String fvOr(JvmLispCompiler.Ctx ctx, String fvName, String generalName) {
		return ctx.usesFloatArray ? fvName : generalName;
	}

	static void compileMake(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2) {
			throw new UnsupportedOperationException("make-array expects at least 1 argument");
		}
		LispVal displacedTo = findKeywordValue(args, LispNames.DISPLACED_TO_KEYWORD);
		if (displacedTo != null) {
			// A displaced view excludes the other keywords (lite semantics; detected at
			// compile time because make-array keywords are literal at the call site).
			if (findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD) != null
					|| findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD) != null
					|| findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD) != null) {
				throw new UnsupportedOperationException(
						"make-array: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element");
			}
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(displacedTo, ctx, className);
			compileKeywordValueOrNull(findKeywordValue(args, LispNames.DISPLACED_INDEX_OFFSET_KEYWORD), ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.MAKE_DISPLACED,
					JvmArrayRuntimeBuilder.MAKE_DISPLACED_DESC);
			return;
		}
		if (findKeywordValue(args, LispNames.DISPLACED_INDEX_OFFSET_KEYWORD) != null) {
			throw new UnsupportedOperationException("make-array: :displaced-index-offset requires :displaced-to");
		}
		LispVal fillPointer = findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD);
		LispVal adjustable = findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD);
		LispVal initValue = findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD);
		LispVal charContentsLowering = LispMacroExpander.lowerCharacterInitialContentsMakeArray(cons);
		if (charContentsLowering != null) {
			// A rank-1 character array built from :initial-contents is a fresh string
			// copy of the contents (a mutable character vector normalizes through the
			// lowering's subseq).
			JvmExprCompiler.compileExpr(charContentsLowering, ctx, className);
			return;
		}
		LispVal contentsLowering = LispMacroExpander.lowerInitialContentsMakeArray(cons);
		if (contentsLowering != null) {
			// :initial-contents lowers to the allocation plus an element-wise fill.
			JvmExprCompiler.compileExpr(contentsLowering, ctx, className);
			return;
		}
		if (LispMacroExpander.isCharacterElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD))) {
			// A rank-1 :element-type 'character array is a mutable character vector,
			// marked by _charVecMake's length-4 header and normalized on demand into the
			// quote-framed runtime string (_strv). A missing fill-pointer defaults to the
			// capacity so aref reads and setf-aref writes see every slot -- uax-15's
			// from-unicode-string is (make-array N :element-type 'character) followed by
			// (setf (aref ...) ch), which requires the mutable representation.
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			compileKeywordValueOrNull(initValue != null ? initValue : new LispChar(' '), ctx, className);
			LispVal effectiveFillPointer = fillPointer != null ? fillPointer : args.get(1);
			compileKeywordValueOrNull(effectiveFillPointer, ctx, className);
			compileKeywordValueOrNull(adjustable, ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.CHAR_VEC_MAKE, JvmArrayRuntimeBuilder.MAKE_DESC);
			return;
		}
		if (ctx.usesFloatArray && isSingleFloatElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD))
				&& fillPointer == null && adjustable == null) {
			// A plain :element-type 'single-float array (no fill pointer / adjustable /
			// displacement) is a packed float[]: _sfvMake(dims, init) allocates it and
			// fills with the coerced (narrowed to f32) init (default 0.0 inside the
			// helper).
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			compileKeywordValueOrNull(initValue, ctx, className);
			invokeHelper(ctx, className, JvmFloatArrayRuntimeBuilder.SINGLE_MAKE,
					JvmFloatArrayRuntimeBuilder.MAKE_DESC);
			return;
		}
		if (ctx.usesFloatArray && isDoubleFloatElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD))
				&& fillPointer == null && adjustable == null) {
			// A plain :element-type 'double-float array (no fill pointer / adjustable /
			// displacement) is a packed double[]: _fvMake(dims, init) allocates it and
			// fills with the coerced init (default 0.0 inside the helper).
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			compileKeywordValueOrNull(initValue, ctx, className);
			invokeHelper(ctx, className, JvmFloatArrayRuntimeBuilder.MAKE, JvmFloatArrayRuntimeBuilder.MAKE_DESC);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (initValue == null && (isDoubleFloatElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD))
				|| isSingleFloatElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD)))) {
			// An :element-type 'double-float / 'single-float array with a fill pointer /
			// adjustable falls back to a general array; default its elements to 0.0, not
			// nil.
			initValue = new LispDouble(0.0);
		}
		compileKeywordValueOrNull(initValue, ctx, className);
		compileKeywordValueOrNull(findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD), ctx, className);
		compileKeywordValueOrNull(findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.MAKE, JvmArrayRuntimeBuilder.MAKE_DESC);
	}

	static void compileArrayBecome(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%array-become old new): adjust old in place to new's shape/contents.
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"%array-become expects 2 arguments, got " + (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.ARRAY_BECOME, JvmArrayRuntimeBuilder.ARRAY_BECOME_DESC);
	}

	static void compileDispTarget(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileUnary(cons, ctx, className, LispNames.ARRAY_DISP_TARGET, JvmArrayRuntimeBuilder.DISP_TARGET,
				JvmArrayRuntimeBuilder.DISP_TARGET_DESC);
	}

	static void compileDispOffset(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileUnary(cons, ctx, className, LispNames.ARRAY_DISP_OFFSET, JvmArrayRuntimeBuilder.DISP_OFFSET,
				JvmArrayRuntimeBuilder.DISP_OFFSET_DESC);
	}

	static void compileFillPointer(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileUnary(cons, ctx, className, LispNames.FILL_POINTER, JvmArrayRuntimeBuilder.FILL_POINTER,
				JvmArrayRuntimeBuilder.FILL_POINTER_DESC);
	}

	static void compileSetFillPointer(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%set-fill-pointer array value): the setf target of (fill-pointer array).
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"%set-fill-pointer expects an array and a value, got " + (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.SET_FILL_POINTER,
				JvmArrayRuntimeBuilder.SET_FILL_POINTER_DESC);
	}

	static void compileHasFillPointer(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileUnary(cons, ctx, className, LispNames.ARRAY_HAS_FILL_POINTER_P, JvmArrayRuntimeBuilder.HAS_FILL_POINTER,
				JvmArrayRuntimeBuilder.HAS_FILL_POINTER_DESC);
	}

	static void compileAdjustableArrayP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileUnary(cons, ctx, className, LispNames.ADJUSTABLE_ARRAY_P, JvmArrayRuntimeBuilder.ADJUSTABLE_ARRAY_P,
				JvmArrayRuntimeBuilder.ADJUSTABLE_ARRAY_P_DESC);
	}

	static void compileVectorPush(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (vector-push value vector)
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"vector-push expects a value and a vector, got " + (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.VECTOR_PUSH, JvmArrayRuntimeBuilder.VECTOR_PUSH_DESC);
	}

	static void compileVectorPop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileUnary(cons, ctx, className, LispNames.VECTOR_POP, JvmArrayRuntimeBuilder.VECTOR_POP,
				JvmArrayRuntimeBuilder.VECTOR_POP_DESC);
	}

	static void compileVectorPushExtend(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (vector-push-extend value vector [extension])
		List<LispVal> args = cons.toList();
		if (args.size() < 3 || args.size() > 4) {
			throw new UnsupportedOperationException(
					"vector-push-extend expects 2 or 3 arguments, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		if (args.size() == 4) {
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		}
		else {
			JvmEmitHelper.compileLong(1, ctx);
		}
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.VECTOR_PUSH_EXTEND,
				JvmArrayRuntimeBuilder.VECTOR_PUSH_EXTEND_DESC);
	}

	private static void compileUnary(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String lispName,
			String helper, String desc) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(lispName + " expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, helper, desc);
	}

	// Compiles the keyword's value expression, or pushes null (nil) when the keyword is
	// absent.
	private static void compileKeywordValueOrNull(@Nullable LispVal value, JvmLispCompiler.Ctx ctx, String className) {
		if (value != null) {
			JvmExprCompiler.compileExpr(value, ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
	}

	static void compileAref(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int rank = args.size() - 2;
		if (rank == 1) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.AREF1, JvmArrayRuntimeBuilder.AREF1),
					JvmArrayRuntimeBuilder.AREF1_DESC);
		}
		else if (rank == 2) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
			invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.AREF2, JvmArrayRuntimeBuilder.AREF2),
					JvmArrayRuntimeBuilder.AREF2_DESC);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			emitSubscriptArray(args, 2, rank, ctx, className);
			invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.AREFN, JvmArrayRuntimeBuilder.AREFN),
					JvmArrayRuntimeBuilder.AREFN_DESC);
		}
	}

	static void compileRowMajorAref(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (row-major-aref array index): the data is stored flat right after the header,
		// so this is exactly the rank-1 accessor, independent of the array's rank.
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"row-major-aref expects an array and an index, got " + (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.AREF1, JvmArrayRuntimeBuilder.AREF1),
				JvmArrayRuntimeBuilder.AREF1_DESC);
	}

	static void compileRowMajorAset(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%row-major-aset array index value): flat store, the rank-1 setter.
		List<LispVal> args = cons.toList();
		if (args.size() != 4) {
			throw new UnsupportedOperationException("%row-major-aset expects an array, an index and a value, got "
					+ (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.ASET1, JvmArrayRuntimeBuilder.ASET1),
				JvmArrayRuntimeBuilder.ASET1_DESC);
	}

	static void compileElementType(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (array-element-type array): double-float for a packed array, else t. Only used
		// when the program uses packed float arrays; otherwise array-element-type expands
		// to the lite (progn array t).
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-element-type expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE,
				JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE_DESC);
	}

	static void compileDims(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-dimensions expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.DIMS, JvmArrayRuntimeBuilder.DIMS),
				JvmArrayRuntimeBuilder.DIMS_DESC);
	}

	static void compileAset(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%aset array subscript... value)
		List<LispVal> args = cons.toList();
		int rank = args.size() - 3;
		LispVal value = args.get(args.size() - 1);
		if (rank == 1) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmExprCompiler.compileExpr(value, ctx, className);
			invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.ASET1, JvmArrayRuntimeBuilder.ASET1),
					JvmArrayRuntimeBuilder.ASET1_DESC);
		}
		else if (rank == 2) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
			JvmExprCompiler.compileExpr(value, ctx, className);
			invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.ASET2, JvmArrayRuntimeBuilder.ASET2),
					JvmArrayRuntimeBuilder.ASET2_DESC);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			emitSubscriptArray(args, 2, rank, ctx, className);
			JvmExprCompiler.compileExpr(value, ctx, className);
			invokeHelper(ctx, className, fvOr(ctx, JvmFloatArrayRuntimeBuilder.ASETN, JvmArrayRuntimeBuilder.ASETN),
					JvmArrayRuntimeBuilder.ASETN_DESC);
		}
	}

	// Packages the rank subscript expressions starting at args[firstSub] into an
	// Object[] (evaluated left to right) for the generic _arefN/_asetN helpers.
	private static void emitSubscriptArray(List<LispVal> args, int firstSub, int rank, JvmLispCompiler.Ctx ctx,
			String className) {
		JvmEmitHelper.emitIntConst(ctx, rank);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		for (int i = 0; i < rank; i++) {
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, i);
			JvmExprCompiler.compileExpr(args.get(firstSub + i), ctx, className);
			ctx.emit(Opcode.AASTORE);
		}
	}

	// Whether a make-array :element-type value designates double-float. On the compile
	// path the value is a literal quoted symbol -- (quote double-float) -- so the quote
	// is
	// unwrapped and the symbol name matched (ignoring any package qualifier).
	private static boolean isDoubleFloatElementType(@Nullable LispVal elementType) {
		return LispNames.DOUBLE_FLOAT.equals(elementTypeLocalName(elementType));
	}

	// Whether a make-array :element-type value designates single-float (packs to a
	// float[]). Same literal quoted-symbol unwrap as isDoubleFloatElementType.
	private static boolean isSingleFloatElementType(@Nullable LispVal elementType) {
		return LispNames.SINGLE_FLOAT.equals(elementTypeLocalName(elementType));
	}

	// The local (package-qualifier-stripped) symbol name of a literal quoted
	// :element-type
	// value, or null when it is not a quoted symbol.
	private static @Nullable String elementTypeLocalName(@Nullable LispVal elementType) {
		LispVal sym = elementType;
		if (sym instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
			sym = rest.car();
		}
		if (sym instanceof LispSymbol s) {
			String name = s.name();
			int colon = name.lastIndexOf(':');
			return colon >= 0 ? name.substring(colon + 1) : name;
		}
		return null;
	}

	private static @Nullable LispVal findKeywordValue(List<LispVal> args, String keyword) {
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && keyword.equals(kw.name())) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	/**
	 * Emits an {@code invokestatic _strv} call normalizing a mutable character vector on
	 * the operand stack into the quote-framed runtime string (any other value passes
	 * through unchanged). A no-op unless the array runtime helpers are emitted -- a
	 * character vector can only be created by {@code make-array}, which raises the same
	 * gate -- so array-free programs stay byte-identical.
	 * @param ctx the compilation context
	 * @param className the generated class name
	 */
	static void emitStrvNormalize(JvmLispCompiler.Ctx ctx, String className) {
		if (!ctx.usesArrays) {
			return;
		}
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.STRV, JvmArrayRuntimeBuilder.STRV_DESC);
	}

	private static void invokeHelper(JvmLispCompiler.Ctx ctx, String className, String name, String desc) {
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
