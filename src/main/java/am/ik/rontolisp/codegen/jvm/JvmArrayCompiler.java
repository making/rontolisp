package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.ArrayElementTypes;
import am.ik.rontolisp.ArrayGrowth;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.macro.LispMacroExpander;
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

	// The rank-1 dispatch chain head: the packed integer-vector helper when the program
	// uses packed integer vectors (it delegates iv -> fv -> general internally), else
	// the fv/general choice. All three tiers share the same descriptor.
	private static String ivOr(JvmLispCompiler.Ctx ctx, String ivName, String fvName, String generalName) {
		return ctx.usesIntArray ? ivName : fvOr(ctx, fvName, generalName);
	}

	// Emits an invokestatic _ivRequireGeneral guard rejecting a packed integer vector
	// with a clear "not applicable" error (the fill-pointer / adjustability /
	// displacement surface never applies to one -- it is always a simple array,
	// mirroring the interpreter's requireGeneralArray; the wasm backend traps on the
	// same shapes). Any other value passes through unchanged. A no-op unless the
	// program uses packed integer vectors, keeping the default build byte-identical.
	private static void emitRequireGeneralIfPacked(JvmLispCompiler.Ctx ctx, String className) {
		if (!ctx.usesIntArray) {
			return;
		}
		invokeHelper(ctx, className, JvmIntArrayRuntimeBuilder.REQUIRE_GENERAL,
				JvmIntArrayRuntimeBuilder.REQUIRE_GENERAL_DESC);
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
			if (nonNilKeyword(args, LispNames.FILL_POINTER_KEYWORD) || nonNilKeyword(args, LispNames.ADJUSTABLE_KEYWORD)
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
		// Resolved ONCE, through the deftype registry: every recognizer below sees the
		// expansion a user alias stands for, so (make-array n :element-type 'octet)
		// picks the same representation as the literal '(unsigned-byte 8) spelling.
		LispVal elementType = LispMacroExpander
			.resolveElementTypeAlias(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD), ctx.closRegistry);
		LispVal runtimeElementTypeLowering = LispMacroExpander.lowerRuntimeElementTypeMakeArray(cons,
				ctx.functions::containsKey);
		if (runtimeElementTypeLowering != null) {
			// A :element-type held in a VARIABLE picks the representation at run time,
			// since no expansion-time recognizer can see it: a call to the
			// %make-array-et prelude helper where that defun is present, the whole
			// seven-arm dispatch inline where it is not.
			JvmExprCompiler.compileExpr(runtimeElementTypeLowering, ctx, className);
			return;
		}
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
		if (LispMacroExpander.isCharacterElementType(elementType)) {
			// A rank-1 :element-type 'character array is a mutable character vector,
			// marked by _charVecMake's length-4 header and normalized on demand into the
			// quote-framed runtime string (_strv). MUTABILITY is the marker's, not the
			// fill pointer's: aref reads and setf-aref writes reach every slot of a
			// character vector with no fill pointer at all -- uax-15's
			// from-unicode-string is (make-array N :element-type 'character) followed by
			// (setf (aref ...) ch), and it needs the representation, not a fill pointer.
			// So a MISSING :fill-pointer leaves the slot nil, exactly as the general
			// array's does: the header's fill-pointer slot is what says the value is NOT
			// a simple string (_strv falls back to dims[0], and %simple-array-p reads
			// the same slot). Defaulting it to the capacity instead made
			// (array-has-fill-pointer-p (make-string 3)) answer t here and nil on the
			// other three backends and in SBCL. The rank is a runtime fact, so
			// _charVecMake does the rank-1 test and falls back to the general
			// representation above it (as _ivMake does).
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			compileKeywordValueOrNull(initValue != null ? initValue : new LispChar(' '), ctx, className);
			compileKeywordValueOrNull(fillPointer, ctx, className);
			compileKeywordValueOrNull(adjustable, ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.CHAR_VEC_MAKE, JvmArrayRuntimeBuilder.MAKE_DESC);
			return;
		}
		int packedIntWidth = packedIntElementWidth(elementType);
		if (ctx.usesIntArray && packedIntWidth > 0 && fillPointer == null && adjustable == null) {
			// A plain :element-type '(unsigned-byte 8|16|32) array (no fill pointer /
			// adjustable / displacement) is a packed long[] with a width header:
			// _ivMake(dims, init, width) allocates it when dims designates rank 1 and
			// falls back to the general representation for rank n (the interpreter's
			// runtime rank check).
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			compileKeywordValueOrNull(initValue, ctx, className);
			JvmEmitHelper.emitIntConst(ctx, packedIntWidth);
			invokeHelper(ctx, className, JvmIntArrayRuntimeBuilder.MAKE, JvmIntArrayRuntimeBuilder.MAKE_DESC);
			return;
		}
		if (ctx.usesFloatArray && isSingleFloatElementType(elementType) && fillPointer == null && adjustable == null) {
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
		if (ctx.usesFloatArray && isDoubleFloatElementType(elementType) && fillPointer == null && adjustable == null) {
			// A plain :element-type 'double-float array (no fill pointer / adjustable /
			// displacement) is a packed double[]: _fvMake(dims, init) allocates it and
			// fills with the coerced init (default 0.0 inside the helper).
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			compileKeywordValueOrNull(initValue, ctx, className);
			invokeHelper(ctx, className, JvmFloatArrayRuntimeBuilder.MAKE, JvmFloatArrayRuntimeBuilder.MAKE_DESC);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// A declared element type that reaches the GENERAL representation -- a packed
		// one combined with :fill-pointer / :adjustable, an (unsigned-byte n) request in
		// a program with no packed-integer gate -- is REMEMBERED on the array, and its
		// own zero is what an unsupplied element takes rather than nil.
		int elementTypeCode = ArrayElementTypes.codeOf(elementType);
		if (initValue == null) {
			initValue = ArrayElementTypes.defaultElement(elementTypeCode);
		}
		compileKeywordValueOrNull(initValue, ctx, className);
		compileKeywordValueOrNull(findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD), ctx, className);
		compileKeywordValueOrNull(findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD), ctx, className);
		if (elementTypeCode == ArrayElementTypes.T) {
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.MAKE, JvmArrayRuntimeBuilder.MAKE_DESC);
			return;
		}
		JvmEmitHelper.emitIntConst(ctx, elementTypeCode);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.MAKE_TYPED, JvmArrayRuntimeBuilder.MAKE_TYPED_DESC);
	}

	static void compileArrayBecome(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%array-become old new): adjust old in place to new's shape/contents.
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"%array-become expects 2 arguments, got " + (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		emitRequireGeneralIfPacked(ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		emitRequireGeneralIfPacked(ctx, className);
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
		emitRequireGeneralIfPacked(ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.SET_FILL_POINTER,
				JvmArrayRuntimeBuilder.SET_FILL_POINTER_DESC);
	}

	static void compileHasFillPointer(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// NOT guarded by _ivRequireGeneral: a packed representation simply HAS no fill
		// pointer, which is nil rather than an error (what CL says of a simple array,
		// and what the interpreter has always answered). _arrayHasFillPointer already
		// answers nil for anything that is not the general ArrayList shape.
		compilePredicate(cons, ctx, className, LispNames.ARRAY_HAS_FILL_POINTER_P,
				JvmArrayRuntimeBuilder.HAS_FILL_POINTER, JvmArrayRuntimeBuilder.HAS_FILL_POINTER_DESC);
	}

	static void compileAdjustableArrayP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// Unguarded for the same reason as array-has-fill-pointer-p above.
		compilePredicate(cons, ctx, className, LispNames.ADJUSTABLE_ARRAY_P, JvmArrayRuntimeBuilder.ADJUSTABLE_ARRAY_P,
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
		emitRequireGeneralIfPacked(ctx, className);
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
		emitRequireGeneralIfPacked(ctx, className);
		if (args.size() == 4) {
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		}
		else {
			// The missing optional argument, as the "not supplied" sentinel the helper
			// turns into the shared default growth policy (am.ik.rontolisp.ArrayGrowth).
			JvmEmitHelper.compileLong(ArrayGrowth.NO_EXTENSION, ctx);
		}
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.VECTOR_PUSH_EXTEND,
				JvmArrayRuntimeBuilder.VECTOR_PUSH_EXTEND_DESC);
	}

	// A unary array PREDICATE: the same shape as compileUnary without the packed guard,
	// for the two questions a packed array answers nil to instead of refusing.
	private static void compilePredicate(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String lispName,
			String helper, String desc) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(lispName + " expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, helper, desc);
	}

	private static void compileUnary(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String lispName,
			String helper, String desc) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(lispName + " expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// Every unary caller is part of the fill-pointer / adjustability / displacement
		// surface, none of which applies to a packed integer vector.
		emitRequireGeneralIfPacked(ctx, className);
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
		if (args.size() == 2) {
			// (aref a): a rank-0 array holds its one element at row-major index 0, so
			// the empty Horner fold is the constant 0 (the arm WasmArrayCompiler has).
			compileAref(
					new LispCons(args.get(0),
							new LispCons(args.get(1), new LispCons(new LispInteger(0), LispNil.INSTANCE))),
					ctx, className);
			return;
		}
		int rank = args.size() - 2;
		if (rank == 1) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			invokeHelper(ctx, className, ivOr(ctx, JvmIntArrayRuntimeBuilder.AREF1, JvmFloatArrayRuntimeBuilder.AREF1,
					JvmArrayRuntimeBuilder.AREF1), JvmArrayRuntimeBuilder.AREF1_DESC);
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
		invokeHelper(ctx, className, ivOr(ctx, JvmIntArrayRuntimeBuilder.AREF1, JvmFloatArrayRuntimeBuilder.AREF1,
				JvmArrayRuntimeBuilder.AREF1), JvmArrayRuntimeBuilder.AREF1_DESC);
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
		invokeHelper(ctx, className, ivOr(ctx, JvmIntArrayRuntimeBuilder.ASET1, JvmFloatArrayRuntimeBuilder.ASET1,
				JvmArrayRuntimeBuilder.ASET1), JvmArrayRuntimeBuilder.ASET1_DESC);
	}

	static void compileElementType(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (array-element-type array): a string answers character (a string is a vector of
		// characters, the one character type); a general array answers what it REMEMBERS
		// being asked for (t unless make-array was told something narrower it could not
		// represent); otherwise the list (unsigned-byte n) for a packed integer vector
		// and double-float/single-float for a packed float array.
		//
		// Only used when the program uses a packed representation or can build a typed
		// general array; otherwise array-element-type expands to the lite
		// (if (stringp array) 'character t). The string check runs first so a string
		// never reaches the dispatch, the ArrayList test separates the general array
		// from the packed ones (a packed general array is an ArrayList too, and its
		// header is where the remembered type lives), and _ivElementType delegates the
		// non-long[] case to _fvElementType when both packed gates are on.
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-element-type expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		// a string answers character; the synthesized name is unspelled (real run-time
		// data, and character is also a function name)
		JvmStringpCompiler.emitStringpCheck(ctx, tempSlot);
		int branchPos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		List<Integer> gotoEnds = new java.util.ArrayList<>();
		if (ctx.usesTypedArray) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")).index());
			int notListPos = ctx.code.size();
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.ELEMENT_TYPE, JvmArrayRuntimeBuilder.ELEMENT_TYPE_DESC);
			gotoEnds.add(ctx.code.size());
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, notListPos, ctx.code.size());
		}
		if (ctx.usesIntArray || ctx.usesFloatArray) {
			// not a string, not a general array: the packed dispatch
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			if (ctx.usesIntArray) {
				invokeHelper(ctx, className, JvmIntArrayRuntimeBuilder.ELEMENT_TYPE,
						JvmIntArrayRuntimeBuilder.ELEMENT_TYPE_DESC);
			}
			else {
				invokeHelper(ctx, className, JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE,
						JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE_DESC);
			}
		}
		else {
			JvmEmitHelper.compileTrue(ctx);
		}
		gotoEnds.add(ctx.code.size());
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int characterPos = ctx.code.size();
		JvmEmitHelper.compileUnspelledLiteral(LispNames.CHARACTER_TYPE, ctx);
		int endPos = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, branchPos, characterPos);
		for (int gotoEnd : gotoEnds) {
			JvmEmitHelper.patchBranch(ctx, gotoEnd, endPos);
		}
	}

	static void compileArrayAlike(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%array-alike seq n): a fresh zero-filled rank-1 array with the SAME
		// representation as seq (packed at the same width, else general) -- the
		// type-preserving allocator behind the shared subseq/copy-seq lowering. Only
		// used when the program uses packed integer vectors; otherwise the shared
		// expandArrayAlikeGeneral lowering applies. Evaluation order: seq then n.
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException("%array-alike expects a sequence and a length");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmIntArrayRuntimeBuilder.ALIKE, JvmIntArrayRuntimeBuilder.ALIKE_DESC);
	}

	static void compileDims(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-dimensions expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, ivOr(ctx, JvmIntArrayRuntimeBuilder.DIMS, JvmFloatArrayRuntimeBuilder.DIMS,
				JvmArrayRuntimeBuilder.DIMS), JvmArrayRuntimeBuilder.DIMS_DESC);
	}

	static void compileAset(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%aset array subscript... value)
		List<LispVal> args = cons.toList();
		if (args.size() == 3) {
			// (%aset a value): the rank-0 store, the twin of the (aref a) arm above.
			compileAset(
					new LispCons(args.get(0),
							new LispCons(args.get(1),
									new LispCons(new LispInteger(0), new LispCons(args.get(2), LispNil.INSTANCE)))),
					ctx, className);
			return;
		}
		int rank = args.size() - 3;
		LispVal value = args.get(args.size() - 1);
		if (rank == 1) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmExprCompiler.compileExpr(value, ctx, className);
			invokeHelper(ctx, className, ivOr(ctx, JvmIntArrayRuntimeBuilder.ASET1, JvmFloatArrayRuntimeBuilder.ASET1,
					JvmArrayRuntimeBuilder.ASET1), JvmArrayRuntimeBuilder.ASET1_DESC);
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

	// The packed integer-vector element width a make-array :element-type argument
	// designates: 8/16/32 for the literal quoted list '(unsigned-byte 8|16|32), else 0.
	// The head symbol name is matched ignoring any package qualifier, like the float
	// widths. Shared with JvmLispCompiler's usesIntArray program scan.
	static int packedIntElementWidth(@Nullable LispVal elementType) {
		LispVal spec = elementType;
		if (spec instanceof LispCons quote && quote.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& quote.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
			spec = rest.car();
		}
		if (spec instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& cons.cdr() instanceof LispCons widthCell && widthCell.car() instanceof am.ik.rontolisp.LispInteger w
				&& widthCell.cdr() instanceof LispNil) {
			String name = head.name();
			int colon = name.lastIndexOf(':');
			String local = colon >= 0 ? name.substring(colon + 1) : name;
			if (local.equals(LispNames.UNSIGNED_BYTE) && (w.value() == 8 || w.value() == 16 || w.value() == 32)) {
				return (int) w.value();
			}
		}
		return 0;
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

	/**
	 * Emits an {@code invokestatic _toMutStr} call converting a fresh runtime string on
	 * the operand stack into a MUTABLE character vector, so the producer's result has a
	 * writable identity like the interpreter's (a non-string passes through unchanged). A
	 * no-op unless the program contains a flipped producer
	 * ({@code MutableStringProducers.programUsesAny}, the same scan the WASM backend
	 * wraps under, and a subset of the array gate -- so the helper is always emitted
	 * where this call is).
	 * @param ctx the compilation context
	 * @param className the generated class name
	 */
	static void emitToMutStr(JvmLispCompiler.Ctx ctx, String className) {
		if (!ctx.mutableStringProducers) {
			return;
		}
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.TO_MUT_STR, JvmArrayRuntimeBuilder.TO_MUT_STR_DESC);
	}

	private static void invokeHelper(JvmLispCompiler.Ctx ctx, String className, String name, String desc) {
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

	// A make-array keyword explicitly given a NON-nil value. An explicit nil
	// (alexandria's ":adjustable nil" beside :displaced-to) asserts exactly what a
	// displaced view already is, so it must not read as a conflicting option.
	private static boolean nonNilKeyword(List<LispVal> args, String keyword) {
		LispVal value = findKeywordValue(args, keyword);
		return value != null && !(value instanceof am.ik.rontolisp.LispNil);
	}

}
