package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Shared helper methods for WASM instruction emission used across all expression
 * compilers.
 */
final class WasmEmitHelper {

	private WasmEmitHelper() {
	}

	/**
	 * The print family's default destination expression: the current value of
	 * {@code *standard-output*} when the program gives it a module global (it binds or
	 * assigns it somewhere -- the redirect contract of
	 * {@code (with-output-to-string (*standard-output*) ...)}), else {@code null} so a
	 * redirect-free program keeps the hard-coded standard output and compiles
	 * byte-identically to before.
	 */
	static am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal defaultStreamArg(WasmLispCompiler.Ctx ctx) {
		return streamArg(ctx, null);
	}

	/**
	 * The destination expression of an output operation, applying CL's stream designator
	 * rule ({@link am.ik.rontolisp.compiler.StreamDesignators}) when the
	 * {@code *standard-output*} redirect is active: an omitted argument and an explicit
	 * nil both denote the current {@code *standard-output*}.
	 * @param ctx the compile context
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile, or {@code null} for the hard-coded standard
	 * output
	 */
	static am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal streamArg(WasmLispCompiler.Ctx ctx,
			am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal explicit) {
		am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal resolved = ctx.globalIndices
			.containsKey(am.ik.rontolisp.LispNames.STANDARD_OUTPUT_VAR)
					? am.ik.rontolisp.compiler.StreamDesignators.resolveOutput(explicit) : explicit;
		return streamDesignator(ctx, resolved);
	}

	/**
	 * The source expression of an INPUT operation, the {@link #streamArg} mirror: an
	 * omitted argument and an explicit nil both denote the current
	 * {@code *standard-input*} when the program binds it somewhere.
	 * @param ctx the compile context
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile, or {@code null} for the hard-coded standard
	 * input
	 */
	static am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal inputStreamArg(WasmLispCompiler.Ctx ctx,
			am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal explicit) {
		am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal resolved = ctx.globalIndices
			.containsKey(am.ik.rontolisp.LispNames.STANDARD_INPUT_VAR)
					? am.ik.rontolisp.compiler.StreamDesignators.resolveInput(explicit) : explicit;
		return streamDesignator(ctx, resolved);
	}

	/**
	 * A stream designator expression resolved down to the raw HANDLE the I/O helpers act
	 * on, for a consumer that takes its stream argument as written (no
	 * {@code *standard-output*} designator rule): a stream VALUE is unwrapped, a synonym
	 * stream is followed. With neither kind reachable in this module the expression is
	 * handed back untouched, so such a program keeps its exact bytes.
	 * @param ctx the compile context
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile
	 */
	static am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal streamDesignator(WasmLispCompiler.Ctx ctx,
			am.ik.rontolisp.@org.jspecify.annotations.Nullable LispVal explicit) {
		if (!ctx.usesSynonymStreams && !ctx.usesStreamValues) {
			return explicit;
		}
		// The shared %STREAM-TARGET defun when the prelude spliced it (one call per
		// stream operation), the inline %obj-* unwrap when it did not -- which happens
		// for a stream value injected after the prelude selection ran.
		return ctx.functions.containsKey(am.ik.rontolisp.LispNames.STREAM_TARGET)
				? am.ik.rontolisp.compiler.StreamDesignators.throughStream(explicit)
				: am.ik.rontolisp.compiler.StreamDesignators.throughStreamInline(explicit);
	}

	/**
	 * Emits a "grow linear memory if it does not yet cover {@code top}" guard. The GC
	 * backend's heap is a bump allocator over {@code HEAP_PTR_ADDR}; without this guard a
	 * large allocation walks past the initial memory size and any access traps with
	 * "memory access out of bounds". This grows memory by whole pages when the
	 * about-to-be-used top address exceeds the current size.
	 *
	 * <p>
	 * It is pure-stack: it allocates no local and adds no function (so every fixed
	 * {@code FUNC_*} index, and the component byte-identical blobs that depend on them,
	 * stay valid). {@code pushTop} must emit code pushing the absolute byte address the
	 * heap is about to use (an {@code i32} computed only from locals / constants / memory
	 * loads, so it can be evaluated twice); it is called once for the test and once to
	 * size the grow. The stack is left as it was found.
	 * @param w the writer for the function body being emitted
	 * @param pushTop emits the i32 top address (idempotent, no net stack effect beyond
	 * the one value it pushes)
	 */
	static void emitGrowHeapTo(WasmWriter w, Runnable pushTop) {
		// neededPages = (top + 65535) >>> 16
		pushTop.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xffff);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(16);
		w.write(Instruction.I32_SHR_U);
		// neededPages > memory.size ?
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.IF, 0x40);
		// memory.grow(neededPages - memory.size); drop the (old size / -1) result
		pushTop.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xffff);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(16);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GROW_MEMORY, 0x00);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
	}

	/**
	 * Opens the EH-mode catch_all wrapper of an EXPORT WRAPPER: {@code block} +
	 * {@code try_table (catch_all 0)}, the catch label being that block. The body
	 * compiled next runs inside the try_table; {@link #emitCatchAllEpilogue} closes the
	 * structure. The normal path exits with a {@code return} from inside the try_table
	 * (see the epilogue), so no result blocktype is needed whatever the function's
	 * signature; the catch_all landing pad falls out of the block into an
	 * {@code unreachable}, preserving the trap shape of an uncaught condition.
	 *
	 * <p>
	 * The program's own entry ({@code _start}/{@code run}) uses
	 * {@link WasmUncaughtReportCompiler} instead, which catches the {@code $lisp-cond}
	 * tag as well and reports the condition before the same trap. A host call's failure
	 * is the host's to report, and a served handler's is the serve loop's, so these
	 * wrappers keep the silent landing.
	 * @param ctx the compilation context (its wasmCtrlDepth is raised by the two opened
	 * control structures)
	 */
	static void emitCatchAllPrologue(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.TRY_TABLE, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.CATCH_ALL);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.wasmCtrlDepth += 2;
	}

	/**
	 * Closes the structure opened by {@link #emitCatchAllPrologue}: {@code return} (the
	 * normal exit, carrying whatever the function's result values are on the stack), the
	 * try_table's and the block's {@code end}, then the {@code unreachable} landing pad.
	 * The caller still writes the function's own final {@code end}.
	 * @param ctx the compilation context
	 */
	static void emitCatchAllEpilogue(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.RETURN);
		ctx.writer.write(Instruction.END); // try_table
		ctx.writer.write(Instruction.END); // block
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.wasmCtrlDepth -= 2;
	}

	/**
	 * Emits an exact-integer literal: an i31 for the fixnum range, else an i64 constant
	 * boxed into a {@code TYPE_BIGNUM} struct (e.g. the {@code #xEFCDAB89} md5 magic
	 * constants). Mirrors the runtime {@code _int_new} normalization, so a literal and a
	 * computed value of the same magnitude share one representation.
	 * @param value the integer value
	 * @param ctx the compilation context
	 */
	static void compileIntegerLiteral(long value, WasmLispCompiler.Ctx ctx) {
		if (value >= WasmBignumRuntimeBuilder.I31_MIN && value <= WasmBignumRuntimeBuilder.I31_MAX) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128((int) value);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			return;
		}
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
	}

	/**
	 * Emits an exact-integer literal outside the signed 64-bit range: the canonical
	 * two's-complement little-endian 32-bit limbs (the same minimal length
	 * {@code _limb_new} produces at runtime, so a literal and a computed value of the
	 * same magnitude share one representation) built with {@code array.new_fixed} into a
	 * {@code TYPE_BIGINT} struct.
	 * @param value the integer value (magnitude outside the {@code long} range)
	 * @param ctx the compilation context
	 */
	static void compileBigIntegerLiteral(java.math.BigInteger value, WasmLispCompiler.Ctx ctx) {
		int n = value.bitLength() / 32 + 1;
		if (n > 10000) {
			// array.new_fixed is capped at 10000 operands; a 320k-bit literal has no
			// business in a program.
			throw new UnsupportedOperationException("Integer literal too large to compile: " + value.bitLength()
					+ " bits (the WASM backend caps literals at " + (10000 * 32) + " bits)");
		}
		for (int i = 0; i < n; i++) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(value.shiftRight(32 * i).intValue());
		}
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_FIXED);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		ctx.writer.writeUnsignedLeb128(n);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGINT);
	}

	static void castI31GetS(WasmLispCompiler.Ctx ctx) {
		castI31GetS(ctx.writer);
	}

	/** The same cast against a raw writer, for a RUNTIME function body. */
	static void castI31GetS(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	/**
	 * Consumes a stream DESIGNATOR on the stack and leaves the {@code i32} the fd-taking
	 * read runtimes ({@code _read_line}, {@code _read}) want: an i31 handle is unboxed (a
	 * WASI fd, or the negative handle of a string input stream), and anything else -- nil
	 * and the {@code t} designator {@code *standard-input*} holds by default -- is
	 * standard input, fd 0. Replaces a bare {@link #castI31GetS} at the read call sites,
	 * where a designator would otherwise TRAP the cast (that trap was the pre-existing
	 * cross-backend divergence of {@code (read-line nil)}). Self-contained
	 * {@code if}/{@code else}/{@code end}, so it needs no {@code wasmCtrlDepth}
	 * bookkeeping.
	 * @param ctx the compilation context
	 */
	static void streamFdOrStdin(WasmLispCompiler.Ctx ctx) {
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		castI31GetS(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Like {@link #castI31GetS} but zero-extends the 31 payload bits. Used for a
	 * linear-memory pointer stored in an i31ref (the {@code --simd} packed float-array
	 * block, see {@link WasmVecSimdRuntimeBuilder}): any address below 2 GiB then
	 * round-trips exactly, where {@code i31.get_s} would sign-extend everything above 1
	 * GiB.
	 */
	static void castI31GetU(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_U);
	}

	/**
	 * Compiles {@code (car x)} (field 0) or {@code (cdr x)} (field 1): nil answers nil, a
	 * cons its field, anything else traps on the cast -- or, in EH mode
	 * ({@link #checksConsFields}), is {@code CAR}'s / {@code CDR}'s type-error. The
	 * spellings of the one shape, chosen by what is being optimized for
	 * ({@code .kb/cons-access-runtime.md}): under {@code --optimize=size} the site is the
	 * operand plus one {@code call} of the shared {@code _car}/{@code _cdr} body;
	 * otherwise the shape is inline -- in EH mode over the operand on the stack, outside
	 * it reading a plain local operand twice where it already lives and spilling any
	 * other operand into a fresh temp first.
	 * @param operand the argument form
	 * @param field the cons field
	 * @param ctx the compile context
	 */
	static void compileConsField(am.ik.rontolisp.LispVal operand, int field, WasmLispCompiler.Ctx ctx) {
		if (ctx.optimize.prefersSizeOverSpeed() || checksConsFields(ctx)) {
			WasmExprCompiler.compileExpr(operand, ctx);
			emitConsField(ctx, field);
			return;
		}
		int slot = WasmExprCompiler.plainLocalSlot(operand, ctx);
		if (slot >= 0) {
			emitInlineConsField(ctx.writer, slot, field);
			return;
		}
		WasmExprCompiler.compileExpr(operand, ctx);
		emitConsField(ctx, field);
	}

	/**
	 * The same as {@link #compileConsField} for an operand already on the stack.
	 * @param ctx the compile context
	 * @param field the cons field
	 */
	static void emitConsField(WasmLispCompiler.Ctx ctx, int field) {
		if (ctx.optimize.prefersSizeOverSpeed()) {
			WasmConsRuntimeBuilder.emitCall(ctx.writer, field);
			return;
		}
		if (checksConsFields(ctx)) {
			emitCheckedConsField(ctx.writer, field, () -> WasmConsRuntimeBuilder.emitCall(ctx.writer, field));
			return;
		}
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		emitInlineConsField(ctx.writer, tmpSlot, field);
	}

	/**
	 * Whether a cons field read checks its operand: in EH mode a non-list is a catchable
	 * {@code type-error} ({@code _type_err_list}) named {@code CAR}/{@code CDR} -- the
	 * checked {@code _car}/{@code _cdr} name themselves, whichever form reached them;
	 * outside it the cast traps, as every other failed cast does there.
	 */
	static boolean checksConsFields(WasmLispCompiler.Ctx ctx) {
		return ctx.operandOpGlobalIndex >= 0;
	}

	/**
	 * Checks the boxed index on the stack ahead of an {@code i31} unboxing (EH mode,
	 * {@link #checksConsFields}), leaving it there: {@code i32.const id; ref.i31; call
	 * _idx_chk} ({@link #buildIndexCheckBody}), so one that is no integer is the
	 * operator's catchable type-error rather than a trapping cast. A no-op outside EH
	 * mode.
	 * @param ctx the compile context
	 */
	static void emitIndexCheck(WasmLispCompiler.Ctx ctx) {
		if (!checksConsFields(ctx)) {
			return;
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmOperandTypes.operatorId(ctx));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_IDX_CHK);
	}

	/**
	 * Checks that the value in {@code slot} is a cons -- or nil too, with {@code orNil}
	 * -- and otherwise signals: in EH mode ({@link #checksConsFields}) the innermost
	 * operator's catchable {@code LIST} type-error ({@code _type_err_list} under its id
	 * in the register), outside it a trap.
	 * {@code local.get slot; ref.test $cons [local.get
	 * slot; ref.is_null; i32.or]; i32.eqz; if <signal>; unreachable end}.
	 * @param ctx the compile context
	 * @param slot the local holding the value
	 * @param orNil whether nil passes too
	 */
	static void emitListCheck(WasmLispCompiler.Ctx ctx, int slot, boolean orNil) {
		WasmWriter w = ctx.writer;
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		if (orNil) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(slot);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.I32_OR);
		}
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		if (checksConsFields(ctx)) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(slot);
			emitListTypeError(ctx);
		}
		else {
			w.write(Instruction.UNREACHABLE);
		}
		w.write(Instruction.END);
	}

	/**
	 * Signals the innermost operator's {@code LIST} type-error over the value on the
	 * stack (EH mode, {@link #checksConsFields}):
	 * {@code i32.const id; global.set $op; call
	 * _type_err_list; unreachable}. Never returns; the stack is polymorphic after it.
	 * @param ctx the compile context
	 */
	static void emitListTypeError(WasmLispCompiler.Ctx ctx) {
		WasmWriter w = ctx.writer;
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmOperandTypes.operatorId(ctx));
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(ctx.operandOpGlobalIndex);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR_LIST);
		w.write(Instruction.UNREACHABLE);
	}

	/**
	 * Builds {@code _idx_chk(index, id) -> index}: a fixnum answers itself at once;
	 * anything else goes through {@code _int_val} with the i31 operator id in the
	 * register -- a non-integer throws that operator's {@code INTEGER} type-error, a wide
	 * integer answers and is answered (its access fails as it always did). A bare
	 * {@code unreachable} outside EH mode, where nothing calls it.
	 * @param operatorGlobal the operator register, or -1 outside EH mode
	 * @return the function body
	 */
	static byte[] buildIndexCheckBody(int operatorGlobal) {
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals: the index and the operator id
		if (operatorGlobal < 0) {
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}
		// a fixnum, an i64 box or a limb integer passes: the access bounds a wide one
		// (_int_val's limb-tier arm would trap on it)
		for (int type : new int[] { Type.I31.code(), WasmLispCompiler.TYPE_BIGNUM, WasmLispCompiler.TYPE_BIGINT }) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(type);
			if (type != Type.I31.code()) {
				w.write(Instruction.I32_OR);
			}
		}
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		castI31GetS(w);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(operatorGlobal);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
		w.write(Instruction.DROP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(operatorGlobal);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _idx_in(subscript, bound) -> i32} ({@code FUNC_IDX_IN}): an i31
	 * subscript in {@code [0, bound)} answers itself unboxed; any other -- negative, at
	 * or past the bound, a wide integer no bound reaches -- is out of range. In EH mode
	 * that is the operator's {@code type-error} whose datum is the subscript and whose
	 * expected type is {@code (INTEGER 0 (bound))}, thrown by {@code _type_err}'s index
	 * arm ({@code WasmOperandTypes}) as kind {@code -1 - bound} under the register the
	 * caller set; outside it -- or in a module whose landing has no index arm -- a trap,
	 * as every failed check is there. On success it clears the operator register the
	 * caller set, so a site needs no clear of its own after the call.
	 * @param operatorGlobal the operator register when the module reports an out-of-range
	 * subscript (EH mode, and the landing's index arm present:
	 * {@code WasmOperandTypes.Operators.indexed}), else -1
	 * @return the function body
	 */
	static byte[] buildIndexBoundBody(int operatorGlobal) {
		boolean landing = operatorGlobal >= 0;
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: the subscript, the bound; one i32 local, the unboxed subscript
		w.writeUnsignedLeb128(1);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		castI31GetS(w);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		if (landing) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.SET_GLOBAL);
			w.writeUnsignedLeb128(operatorGlobal);
		}
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);
		if (landing) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(-1);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.I32_SUB);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR);
			w.write(Instruction.DROP);
		}
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * The checked cons field read of the list on the stack: a cons yields its field in
	 * ONE type test, and a value that is no cons -- nil or a wrong type -- is handed,
	 * still on the stack, to {@code miss}, which pushes what it answers (at an inline
	 * site the checked {@code _car}/{@code _cdr} body, which answers nil for nil and
	 * signals {@code CAR}'s / {@code CDR}'s type-error otherwise):
	 * {@code block (eqref -> eqref)
	 * block (eqref -> eqref) br_on_cast_fail 0 eqref (ref $cons); struct.get $cons field;
	 * br 1 end <miss> end}. Both blocks take the operand as their parameter, so the site
	 * needs no local of its own.
	 * @param w the writer
	 * @param field the cons field
	 * @param miss pushes the answer for the non-cons operand it finds on the stack
	 */
	static void emitCheckedConsField(WasmWriter w, int field, Runnable miss) {
		w.write(Instruction.BLOCK);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CALLABLE_BASE);
		w.write(Instruction.BLOCK);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CALLABLE_BASE);
		w.write(Instruction.GC_PREFIX, Instruction.BR_ON_CAST_FAIL);
		w.write(0x01); // the operand nullable, the cast not
		w.writeUnsignedLeb128(0);
		w.writeHeapType(Type.EQ.code());
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.END);
		miss.run();
		w.write(Instruction.END);
	}

	/**
	 * The inline shape over the list in {@code slot}: {@code local.get slot; ref.is_null;
	 * if (result eqref) ref.null eq else local.get slot; ref.cast $cons; struct.get $cons
	 * field end}. Shared with the {@code _car}/{@code _cdr} bodies, so a call and an
	 * inline site cannot drift apart.
	 */
	static void emitInlineConsField(WasmWriter w, int slot, int field) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
		w.write(Instruction.END);
	}

	/**
	 * Runtime type check: convert (ref eq) on stack to f64. If i31ref (integer), converts
	 * via f64.convert_i32_s. If a boxed integer ({@code TYPE_BIGNUM}), converts its i64
	 * field. If a ratio struct, divides numerator by denominator as f64. If float_struct,
	 * extracts f64 field.
	 */
	static void castFloatGetF64(WasmLispCompiler.Ctx ctx) {
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_AS_F64);
	}

	/**
	 * Boxes the {@code f64} on the stack into a {@code TYPE_FLOAT} struct -- the float
	 * math sites' idiom, since the body compiler only allocates {@code (ref null eq)}
	 * temporaries.
	 * @param ctx the compile context
	 */
	static void boxF64(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	/**
	 * Loads {@code local[slot]} (a {@code TYPE_FLOAT} struct) and pushes its {@code f64}
	 * field.
	 * @param ctx the compile context
	 * @param slot the boxed temporary
	 */
	static void unboxF64Local(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeUnsignedLeb128(0);
	}

	/**
	 * Emits {@code call FUNC_AS_F64}: consumes the {@code (ref null eq)} on the stack and
	 * leaves its {@code f64} value.
	 *
	 * <p>
	 * The dispatch itself is {@link #buildAsF64Body} -- ONE shared function rather than
	 * the ~80-byte ladder every site used to inline. The float arithmetic of
	 * {@code size-report/programs/pi_approx} reached it ten times in one five-line
	 * program, and the whole module carried 26 copies, 43% of its code section
	 * ({@code .kb/wasm-shared-coercion.md}). Out of line it also stops allocating a
	 * scratch temp per site, which the caller never released.
	 * @param w the body writer
	 */
	static void castFloatGetF64(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_AS_F64);
	}

	/**
	 * Builds {@code _as_f64} (FUNC_AS_F64): the value in {@code local[slot]} as an
	 * {@code f64}, dispatching on its runtime type -- an i31 integer converts directly, a
	 * {@code TYPE_BIGNUM} converts its {@code i64} field, a limb {@code TYPE_BIGINT} goes
	 * through {@code _big_to_f64}, a ratio divides numerator by denominator (float
	 * contagion), and anything else is cast to a {@code TYPE_FLOAT} struct and read.
	 * @param w the body writer
	 * @param slot the {@code (ref null eq)} local holding the value
	 */
	static void emitAsF64FromLocal(WasmWriter w, int tmpSlot) {
		// The FLOAT rung is FIRST: this function exists for float contagion, so its
		// argument usually IS a float -- the historical i31-first order made every
		// float pay four failed ref.tests before its cast, and adding the non-number
		// check as a fifth measured +9.8% on the mandelbrot benchmark (2026-08-31,
		// .kb/wasm-shared-coercion.md). Float-first, a float pays ONE test + cast
		// (faster than the pre-check ladder); an i31 coerced by a mixed int-float
		// operation pays one extra test, measured back to baseline.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF);
		w.write(Type.F64);
		// float_struct path: cast, extract f64 field
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.F64);
		// i31 path: cast to i31, get_s, convert to f64
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.ELSE);
		// boxed-integer path: convert the i64 field
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.IF);
		w.write(Type.F64);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONVERT_S_I64);
		w.write(Instruction.ELSE);
		// limb-integer path: the float approximation via _big_to_f64
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.IF);
		w.write(Type.F64);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_TO_F64);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF);
		w.write(Type.F64);
		// ratio path: numerator / denominator as f64 (float contagion)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.ELSE);
		// A complex reaching the f64 coercion is not silently reduced to its real
		// part (that would be a wrong number): it lands in _type_err_real, like a
		// complex reaching any other real-only funnel on every backend (the
		// interpreter's requireRealOperand, the JVM backend's _dbl holder arm).
		// Complex arithmetic never reaches here for a syntactically visible complex
		// (the call sites steer it to the _c* helpers, whose parts -- always real
		// -- flow through this same function).
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.IF);
		w.write(Type.F64);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.ELSE);
		// NON-number landing: _type_err_num throws a catchable $lisp-cond in EH
		// mode ("The value <prin1> is not of type NUMBER") and is a bare `unreachable`
		// outside it -- the arm that replaced the uncatchable cast-failure trap.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(tmpSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * Builds the {@code _as_f64} function body: the ladder over its single parameter.
	 * @return the function body (signature {@code (eqref) -> f64}, TYPE_BIG_TO_F64)
	 */
	static byte[] buildAsF64Body() {
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no extra locals: the parameter IS the slot the ladder reads
		emitAsF64FromLocal(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits the Lisp boolean true. It is the symbol {@code t} (represented at runtime as
	 * a TYPE_STRING struct pointing at {@code "t"}, like any other symbol), so it prints
	 * as {@code t} and is {@code eq} to a quoted {@code 't}, matching the interpreter.
	 * Loaded through the {@code _t_sym} cache helper -- one shared instance with the
	 * interned id, instead of a fresh {@code _str_build} allocation per true result (todo
	 * 194 stage 3: a loop's termination test allocated every iteration).
	 */
	static void emitTrue(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_T_SYM);
	}

	/**
	 * A function body that answers {@code nil} (ref.null eq) for any argument list -- the
	 * stub a fixed-index runtime helper gets when its real body would call an import the
	 * program does not declare, so the function index stays valid while nothing calls it.
	 * @return the encoded body bytes
	 */
	static byte[] buildNilBody() {
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Converts an i32 (0=false, non-0=true) on the WASM stack into a Lisp boolean
	 * (ref.null eq = nil, or the symbol {@code t}).
	 */
	static void emitBoolFromI32(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		emitTrue(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Emits {@code struct.new TYPE_CONS} over the car and cdr on the stack. In a module
	 * whose objects carry the identity-hash slot ({@code Ctx.usesIdentityHashTables},
	 * {@code .kb/hash-tables.md}) the cons has a third, {@code (mut i32)} field, so the
	 * slot's unassigned {@code 0} is pushed first; every cons allocation goes through
	 * here, so no site can disagree with the type section about the shape.
	 * @param w the writer
	 * @param identityHash whether the module's conses carry the identity-hash slot
	 */
	static void emitNewCons(WasmWriter w, boolean identityHash) {
		if (identityHash) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
		}
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	/** {@link #emitNewCons(WasmWriter, boolean)} for the context's writer and shape. */
	static void emitNewCons(WasmLispCompiler.Ctx ctx) {
		emitNewCons(ctx.writer, ctx.usesIdentityHashTables);
	}

	/**
	 * Emits {@code struct.new TYPE_CELL} over the value on the stack; the cell's
	 * identity-hash slot rides behind the value exactly as the cons's does behind the
	 * cdr.
	 * @param w the writer
	 * @param identityHash whether the module's cells carry the identity-hash slot
	 */
	static void emitNewCell(WasmWriter w, boolean identityHash) {
		if (identityHash) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
		}
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	/** {@link #emitNewCell(WasmWriter, boolean)} for the context's writer and shape. */
	static void emitNewCell(WasmLispCompiler.Ctx ctx) {
		emitNewCell(ctx.writer, ctx.usesIdentityHashTables);
	}

	/**
	 * Emits {@code struct.new TYPE_CLOSURE} over the funcId and env on the stack; the
	 * closure's identity-hash slot rides behind the env exactly as the cons's does behind
	 * the cdr.
	 * @param w the writer
	 * @param identityHash whether the module's closures carry the identity-hash slot
	 */
	static void emitNewClosure(WasmWriter w, boolean identityHash) {
		if (identityHash) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
		}
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
	}

	/**
	 * {@link #emitNewClosure(WasmWriter, boolean)} for the context's writer and shape.
	 */
	static void emitNewClosure(WasmLispCompiler.Ctx ctx) {
		emitNewClosure(ctx.writer, ctx.usesIdentityHashTables);
	}

	/**
	 * Emits {@code struct.new TYPE_INSTANCE} over the layout address and slots array on
	 * the stack; the identity-hash slot is the instance's third field.
	 * @param w the writer
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index
	 * @param identityHash whether the module's instances carry the identity-hash slot
	 */
	static void emitNewInstance(WasmWriter w, int instanceTypeIndex, boolean identityHash) {
		if (identityHash) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
		}
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(instanceTypeIndex);
	}

	/**
	 * {@link #emitNewInstance(WasmWriter, int, boolean)} for the context's writer and
	 * shape.
	 */
	static void emitNewInstance(WasmLispCompiler.Ctx ctx) {
		emitNewInstance(ctx.writer, ctx.instanceTypeIndex, ctx.usesIdentityHashTables);
	}

	static void emitBoxLocal(WasmLispCompiler.Ctx ctx, int slot) {
		// Box: load value, create cell, store cell back
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.emitNewCell(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	static void emitLoadCapture(WasmLispCompiler.Ctx ctx, int depth) {
		// Navigate env cons list to depth, get car (cell), then unbox
		emitLoadCaptureCell(ctx, depth);
		// Unbox from cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeUnsignedLeb128(0);
	}

	static void emitLoadCaptureCell(WasmLispCompiler.Ctx ctx, int depth) {
		// Load env from slot 0
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ctx.closureEnvSlot);
		// Navigate through cons list: cdr depth times
		for (int i = 0; i < depth; i++) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeUnsignedLeb128(1); // cdr
		}
		// Get car (the cell)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0); // car = cell
		// Cast to cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
	}

	/**
	 * Load the cell (boxed reference) for a variable, for building closure env.
	 */
	static void emitLoadVarCell(String varName, WasmLispCompiler.Ctx ctx) {
		// First check if it's a boxed local
		Integer slot = ctx.locals.get(varName);
		if (slot != null && ctx.boxedVars.contains(varName)) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			// The local IS the cell
			return;
		}
		// Check if it's a capture (already a cell in the env)
		Integer captureIdx = ctx.captures.get(varName);
		if (captureIdx != null) {
			emitLoadCaptureCell(ctx, captureIdx);
			return;
		}
		// Unboxed local: create a new cell
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			WasmEmitHelper.emitNewCell(ctx);
			return;
		}
		throw new UnsupportedOperationException("Cannot find variable for closure: " + varName);
	}

	/**
	 * Compares two (ref null eq) values on the stack for {@code eql} -- and {@code eq},
	 * the same predicate on every backend ({@code .kb/eq-numbers.md}: a number compares
	 * by type and value, never by box identity). Produces an i32 (0=false, 1=true):
	 * {@code ref.eq} inline (identical references, which covers every i31 integer, the
	 * nil/t singletons and the same cons), then inline the symbol/string offset compare
	 * and the i31 miss, and otherwise one call to {@code _eql_tail}
	 * ({@link WasmLispCompiler#FUNC_EQL_TAIL}), which compares characters and numbers of
	 * the same type and value.
	 * @param ctx the compilation context
	 */
	static void emitEqlComparison(WasmLispCompiler.Ctx ctx) {
		int aSlot = ctx.allocTemp();
		int bSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(aSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(aSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bSlot);
		ctx.writer.write(Instruction.REF_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.ELSE);
		// The two misses that dominate stay inline, so they cost no call: a symbol or
		// string (compared by interned offset, and never eql to anything else) and an
		// i31 fixnum (eql only to itself, which ref.eq already refused).
		emitRefTest(ctx, aSlot, WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		emitRefTest(ctx, bSlot, WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		emitStringOffset(ctx, aSlot);
		emitStringOffset(ctx, bSlot);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		emitRefTest(ctx, aSlot, Type.I31.code());
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(aSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQL_TAIL);
		ctx.writer.write(Instruction.END); // end i31 if
		ctx.writer.write(Instruction.END); // end string if
		ctx.writer.write(Instruction.END); // end ref.eq if
	}

	private static void emitRefTest(WasmLispCompiler.Ctx ctx, int slot, int heapType) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(heapType);
	}

	private static void emitStringOffset(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeUnsignedLeb128(0);
	}

	static void compileStringLiteral(String displayForm, WasmLispCompiler.Ctx ctx) {
		// The emitted value is a literal the program can hold at run time, so its
		// spelling is a designator the dispatch gate's name probes must see.
		internSpelledLiteral(displayForm, ctx);
		compileUnspelledLiteral(displayForm, ctx);
	}

	/**
	 * Records a literal's spelling the way {@link #compileStringLiteral} does and interns
	 * its bytes in the data segment, WITHOUT emitting the build -- the half a call site
	 * that consumes the bytes rather than the Lisp value needs (the literal
	 * {@code :string} host-import lowering, {@code WasmImportCompiler}).
	 *
	 * <p>
	 * The spelling is recorded even though this caller never produces a Lisp string from
	 * it: the record is what the dispatch gate's name probes read, and keeping it makes
	 * the lowering a change of INSTRUCTIONS only -- no site can close a gate the general
	 * emission would have held open.
	 * @param displayForm the framed string (or symbol name) to intern
	 * @param ctx the compilation context
	 * @return the data-segment entry (offset and BYTE length of the framed form)
	 */
	static WasmLispCompiler.StringTable.StringEntry internSpelledLiteral(String displayForm, WasmLispCompiler.Ctx ctx) {
		ctx.spelledLiterals.add(displayForm);
		if (!ctx.injectedRuntimeBody) {
			// ... and the registry gate needs to know whether the USER's text is what
			// spells it: an injected wrapper body quotes 'list / 'cons / 'string as type
			// designators for its own coerce calls, and those collide with the wrapper
			// defun names without ever being a function designator
			// (Ctx.userSpelledLiterals).
			ctx.userSpelledLiterals.add(displayForm);
		}
		return ctx.stringTable.addString(displayForm);
	}

	/**
	 * {@link #compileStringLiteral} minus the spelled-literal record: the emission for a
	 * name the COMPILER synthesized ({@code %unspelled-quote}), which must not arm the
	 * funcall-dispatch gate's name probes. See {@code LispNames.UNSPELLED_QUOTE}.
	 * @param displayForm the symbol name (or framed string) to build
	 * @param ctx the compilation context
	 */
	static void compileUnspelledLiteral(String displayForm, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.StringTable.StringEntry entry = ctx.stringTable.addString(displayForm);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.length());
		emitStrBuildCall(ctx.writer);
	}

	/**
	 * Emits {@code call FUNC_STR_BUILD}: consumes an {@code (off, len)} pair on the stack
	 * (a linear byte offset + byte length) and pushes the {@code TYPE_STRING} built by
	 * copying {@code linear[off..off+len)} into a fresh {@code $str_bytes} GC array (id =
	 * off). A drop-in replacement for the old two-field {@code struct.new TYPE_STRING}
	 * (which popped the same {@code (offset, length)} pair), so every string/symbol build
	 * moves its bytes onto the GC heap. See
	 * {@link WasmStringRuntimeBuilder#buildStrBuildBody()}.
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrBuildCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_BUILD);
	}

	/**
	 * Emits {@code call FUNC_STR_FRESH}: the same {@code (off, len) -> TYPE_STRING} shape
	 * as {@link #emitStrBuildCall(WasmWriter)}, but the resulting string's id is a fresh
	 * monotonic counter value rather than {@code off}. Every RUNTIME string build uses
	 * this so that reusing the assembly scratch offset (the heap is a stack now) cannot
	 * make two distinct runtime strings / uninterned symbols compare {@code eq}. Interned
	 * names (literals, {@code _intern}'d symbols, t) keep using {@code emitStrBuildCall}.
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrFreshCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_FRESH);
	}

	/**
	 * Emits {@code call FUNC_STR_TO_MEM}: consumes a {@code (str, ptr)} pair (a
	 * {@code TYPE_STRING} ref and a linear byte offset) and pushes the byte count after
	 * copying the string's {@code $str_bytes} array (quotes included) into
	 * {@code linear[ptr..)}. Used where a linear pointer is still required (WASI iovecs,
	 * the reader input scratch, the fetch wire, the host {@code :string} boundary,
	 * intern).
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrToMemCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_TO_MEM);
	}

	/**
	 * Emits {@code call FUNC_WRITE_STR_GC}: consumes a {@code (str, from, to, esc)}
	 * quadruple and writes bytes {@code [from, to)} of the string value to the current
	 * print sink from its GC array (capture buffer append or stdout). The print path for
	 * string values. With {@code esc = 1} the range is the string's CONTENT and the
	 * callee writes the readable form around it -- the frame quotes plus a {@code \}
	 * before every embedded {@code "} / {@code \}.
	 * @param w the writer for the function body being emitted
	 */
	static void emitWriteStrGcCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR_GC);
	}

	/**
	 * Emits {@code call FUNC_SYM_ESC_GC} (todo 626): {@code _print_val}'s bare-symbol arm
	 * calls this instead of {@link #emitWriteStrGcCall} so a symbol name that is not
	 * upcase-invariant, or that holds a non-constituent byte, prints
	 * {@code |...|}-framed. Same {@code (str, from, to, unused)} stack shape as
	 * {@link #emitWriteStrGcCall}.
	 */
	static void emitSymEscGcCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_SYM_ESC_GC);
	}

	/**
	 * Emits {@code call FUNC_CHARVEC_TO_STR}: replaces a mutable character vector on the
	 * stack with the equivalent quote-framed runtime string; any other value passes
	 * through unchanged. Inserted after the string operand of every string consumer
	 * (char, subseq, string=/-equal, case/trim/concat, write-string, read-from-string,
	 * intern, make-symbol) so a fill-pointered/adjustable character vector behaves as a
	 * string there -- and NOTHING at all when
	 * {@link WasmLispCompiler.Ctx#charvecPossible} says the program can never make one.
	 * See {@link WasmStringRuntimeBuilder#buildCharvecToStrBody()}.
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	/**
	 * Fails the compile when a charvec CONSTRUCTOR is reached in a module whose
	 * {@link WasmLispCompiler.Ctx#charvecPossible} gate said nothing can be a mutable
	 * character vector. The gate is an allowlist over the program's operators, so this is
	 * unreachable: an injected runtime body is compiled with the flag forced ON, and the
	 * user's own program reaches a constructor only by spelling an operator the allowlist
	 * does not know. If an entry that CAN construct one ever joins that list, this is
	 * where the build has to stop -- past it the missing normalization is a SILENT wrong
	 * answer at the host boundary rather than a crash (the discipline
	 * {@code SpecialVarCollector} under-collection gets on the JVM).
	 * @param ctx the compilation context
	 * @param site what is being emitted, for the message
	 */
	/**
	 * The second half of {@link #requireCharvecPossible}: fails the compile when the
	 * program's OWN code calls an injected runtime defun while the charvec gate is
	 * closed. Those bodies -- the built-in wrapper catalog and the shared sequence
	 * helpers -- are compiled as if a character vector were possible, which is sound only
	 * while nothing can reach them: an allowlisted operator neither constructs one nor
	 * lowers to a helper that does, and no catalog name is spelled (spelling it would
	 * open the gate). A call from the program is the one shape that would break that, so
	 * it stops here instead of shipping a module whose fixed runtime no longer normalizes
	 * what the helper built.
	 * @param ctx the compilation context
	 * @param name the callee
	 */
	static void requireNoCharvecHelper(WasmLispCompiler.Ctx ctx, String name) {
		if (!ctx.charvecPossible && !ctx.injectedRuntimeBody && ctx.injectedRuntimeDefunNames.contains(name)) {
			throw new IllegalStateException("internal: " + name
					+ " is injected runtime, compiled as if a character vector were possible, and the program "
					+ "reaches it with the charvec gate closed -- either an operator that lowers to it is "
					+ "wrongly on CHARVEC_FREE_OPERATORS, or the program defines a function on that operator's "
					+ "name that the backend intercepts as a `cl` operator instead of dispatching to (see "
					+ "ClRedefinitionWarnings#redefinesClFunction); check both before changing the allowlist");
		}
	}

	static void requireCharvecPossible(WasmLispCompiler.Ctx ctx, String site) {
		if (!ctx.charvecPossible) {
			throw new IllegalStateException("internal: " + site
					+ " can make a mutable character vector in a program whose gate said none was possible -- "
					+ "either an operator on CHARVEC_FREE_OPERATORS constructs one, or a name the program "
					+ "defines is a `cl` function the backend intercepts as an operator instead of dispatching "
					+ "to (see ClRedefinitionWarnings#redefinesClFunction); check both before changing the "
					+ "allowlist");
		}
	}

	static void emitCharvecToStrCall(WasmLispCompiler.Ctx ctx) {
		emitCharvecToStrCall(ctx.writer, ctx.charvecPossible);
	}

	/**
	 * The raw-{@link WasmWriter} counterpart of
	 * {@link #emitCharvecToStrCall(WasmLispCompiler.Ctx)}, for the runtime builders
	 * ({@code _equal}/{@code _hash}/{@code _print_val}/{@code _princ_val} normalize their
	 * argument at entry through it). The flag is a PARAMETER rather than a read of some
	 * shared state so that no fixed runtime body can emit the call -- and with it root
	 * the 1,961-byte normalization group -- in a module where nothing can be a character
	 * vector.
	 * @param w the writer for the function body being emitted
	 * @param charvecPossible {@link WasmLispCompiler.Ctx#charvecPossible}: nothing is
	 * emitted when it is false
	 */
	static void emitCharvecToStrCall(WasmWriter w, boolean charvecPossible) {
		if (!charvecPossible) {
			return;
		}
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_CHARVEC_TO_STR);
	}

	/**
	 * Emits {@code call FUNC_CHARVEC_P}: replaces a value on the stack with the i32
	 * {@code 1}/{@code 0} answer to "is this a mutable character vector?". The shape half
	 * of {@link #emitCharvecToStrCall(WasmLispCompiler.Ctx)}, for a caller that wants the
	 * bit and not the string -- it runs in constant time and allocates nothing, where the
	 * normalization renders every element. {@code stringp} is the caller that matters.
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	/**
	 * Emits {@code call FUNC_TO_MUT_STR}: converts a fresh runtime string on the stack
	 * into a MUTABLE character vector, so the producer's result has a writable identity
	 * like the interpreter's (a non-string passes through unchanged). A no-op unless the
	 * program contains a flipped producer ({@code MutableStringProducers.programUsesAny}
	 * -- the same scan the JVM backend wraps under).
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitToMutStrCall(WasmLispCompiler.Ctx ctx) {
		if (!ctx.mutableStringProducers) {
			return;
		}
		// The third charvec CONSTRUCTOR (the other two are WasmSubseqCompiler's
		// _subseq_str route and WasmArrayCompiler.compileMake's marker).
		requireCharvecPossible(ctx, "a flipped string producer");
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_TO_MUT_STR);
	}

	static void emitCharvecPCall(WasmLispCompiler.Ctx ctx) {
		emitCharvecPCall(ctx.writer);
	}

	/**
	 * The raw-{@link WasmWriter} counterpart of
	 * {@link #emitCharvecPCall(WasmLispCompiler.Ctx)}, for the runtime builders
	 * ({@code _charvec_to_str} opens with it).
	 * @param w the writer for the function body being emitted
	 */
	static void emitCharvecPCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_CHARVEC_P);
	}

	/**
	 * Emits {@code call FUNC_STR_CHAR_COUNT}: replaces a {@code TYPE_STRING} value on the
	 * stack with the i32 character count of its UTF-8 encoded content (byte count minus
	 * two surrounding quotes, walking one increment per UTF-8 lead byte). Every
	 * character-based length reads through it.
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitStrCharCountCall(WasmLispCompiler.Ctx ctx) {
		emitStrCharCountCall(ctx.writer);
	}

	/**
	 * The raw-{@link WasmWriter} counterpart of
	 * {@link #emitStrCharCountCall(WasmLispCompiler.Ctx)}, for the runtime builders that
	 * emit into a {@code WasmWriter} directly.
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrCharCountCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_CHAR_COUNT);
	}

	/**
	 * Emits {@code call FUNC_STR_CHAR_AT}: consumes a {@code (str, i)} pair (a
	 * {@code TYPE_STRING} and an i32 character index) and pushes the i32 code point of
	 * the {@code i}-th character, walking UTF-8 to find the character then decoding its
	 * 1-4 byte sequence. Every {@code (char s i)}/{@code (schar s i)} lowering routes
	 * through it.
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitStrCharAtCall(WasmLispCompiler.Ctx ctx) {
		emitStrCharAtCall(ctx.writer);
	}

	/**
	 * The runtime-builder half of {@link #emitStrCharAtCall(WasmLispCompiler.Ctx)}, for a
	 * body being written straight to a {@link WasmWriter}.
	 * @param w the writer receiving the instructions
	 */
	static void emitStrCharAtCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_CHAR_AT);
	}

	/**
	 * Given a value on the stack that is a {@code TYPE_STRING}, replaces it with its
	 * {@code $str_bytes} data array (field 2), cast to the concrete array type so callers
	 * can {@code array.get_u} / {@code array.len} it. The array holds the same
	 * quote-framed bytes the string's linear representation held, so a byte at old
	 * {@code linear[id+i]} is now {@code array[i]}. Emits
	 * {@code ref.cast TYPE_STRING; struct.get 2; ref.cast
	 * $str_bytes} (the cast to TYPE_STRING is redundant when the value is already a
	 * TYPE_STRING ref, but harmless).
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitStrBytesArray(WasmLispCompiler.Ctx ctx) {
		emitStrBytesArray(ctx.writer);
	}

	/**
	 * The raw-{@link WasmWriter} counterpart of
	 * {@link #emitStrBytesArray(WasmLispCompiler.Ctx)}, for the runtime builders that
	 * emit into a {@code WasmWriter} directly. Consumes a {@code TYPE_STRING} ref on the
	 * stack and leaves its {@code $str_bytes} data array (field 2), cast to the concrete
	 * array type so callers can {@code array.get_u} / {@code array.len} it.
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrBytesArray(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STR_BYTES);
	}

}
