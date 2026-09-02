package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.rontolisp.ArrayElementTypes;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The GENERAL-array arm of every element access, and the dimension parse every ALLOCATION
 * shares -- both emitted once per module instead of at each site.
 *
 * <p>
 * A general array is a cell whose field 0 is the header cons
 * {@code (dims . (meta . data))}. When {@code data} is itself a cell the array is a
 * DISPLACED VIEW: the read has to add that view's offset (the cddr of its meta) to the
 * index and continue at the target's header, repeatedly, until a header whose data slot
 * is the buckets array is reached. That walk is a loop of about forty-five instructions,
 * and it used to be spelled at every {@code aref} / {@code row-major-aref} /
 * {@code %aset} / {@code %row-major-aset} -- together with the two never-released temps
 * it needs. Here it is two functions:
 *
 * <ul>
 * <li>{@code _arr_get (header, flat) -> value} ({@link WasmLispCompiler#FUNC_ARR_GET},
 * reusing {@code TYPE_BIG_SHIFT}),</li>
 * <li>{@code _arr_set (header, flat, value) -> value}
 * ({@link WasmLispCompiler#FUNC_ARR_SET}, {@code TYPE_ARR_SET}) -- answering the value it
 * stored, which is what the accessors leave on the stack.</li>
 * </ul>
 *
 * <p>
 * The packed float and packed integer arms deliberately stay inline at the site: the
 * integer one is the fused raw-{@code i64} store (`.kb/packed-integer-vectors.md`), which
 * a call would give up. Same lesson as {@code .kb/wasm-shared-coercion.md} -- when a
 * per-site expansion grows past a few hundred bytes, it becomes a callee.
 *
 * <p>
 * The allocation half is three more, shared by every {@code make-array} shape -- general,
 * general with {@code :fill-pointer}/{@code :adjustable}, packed float, packed integer
 * and the {@code :displaced-to} view:
 *
 * <ul>
 * <li>{@code _arr_dims (dims) -> buckets} ({@link WasmLispCompiler#FUNC_ARR_DIMS}) -- the
 * dimension argument as a buckets array of i31 sizes,</li>
 * <li>{@code _arr_total (buckets) -> i31} ({@link WasmLispCompiler#FUNC_ARR_TOTAL}) --
 * the product of that array, which is both the allocation size and the bound a displaced
 * view is checked against,</li>
 * <li>{@code _arr_fp (fp, buckets) -> i31 | null} ({@link WasmLispCompiler#FUNC_ARR_FP})
 * -- the {@code :fill-pointer} argument resolved against the shape.</li>
 * </ul>
 *
 * <p>
 * All three reuse existing callable signatures, so no type index moves. The rank-1
 * integer shorthand is NOT here: it is three instructions and the shape nearly every
 * allocation writes, so {@code WasmArrayCompiler.emitParseDims} keeps it inline and only
 * the list arm calls (`.kb/array-literals.md` has the per-site bytes and the timings).
 */
final class WasmArrayRuntimeBuilder {

	private WasmArrayRuntimeBuilder() {
	}

	/**
	 * Builds {@code _arr_get (header, flat) -> value}: the displacement walk, then
	 * {@code buckets[index]}.
	 * @return the function body (signature {@code ((ref null eq), i32) -> (ref null eq)},
	 * {@code TYPE_BIG_SHIFT})
	 */
	static byte[] buildArrGetBody(boolean simd) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// Two extra locals: the walk cursor (the header currently being examined) and
		// the PACKED target the chain may end on.
		declareEqrefLocals(w, 2);
		int curSlot = 2, targetSlot = 3;
		emitResolve(w, curSlot, 1);
		// A STRING VIEW's data slot holds the string it aliases: read character `flat`
		// through the shared UTF-8 accessor and box it as the runtime CHARACTER.
		w.write(Instruction.BLOCK, 0x40);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		emitDataSlot(w, curSlot);
		get(w, 1);
		WasmEmitHelper.emitStrCharAtCall(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // block
		// A view over a PACKED target: the data slot IS that target and the elements
		// live unboxed in it.
		w.write(Instruction.BLOCK, 0x40);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.BR_IF, 0);
		emitDataSlot(w, curSlot);
		set(w, targetSlot);
		emitPackedTargetRead(w, targetSlot, 1, simd);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // block
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * Builds {@code _arr_set (header, flat, value) -> value}: the displacement walk, then
	 * {@code buckets[index] = value}, answering the value.
	 * @return the function body (signature
	 * {@code ((ref null eq), i32, (ref null eq)) -> (ref null eq)},
	 * {@link WasmLispCompiler#TYPE_ARR_SET})
	 */
	static byte[] buildArrSetBody(boolean simd) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// locals: 3 = cur, 4 = promoted, 5 = str, 6 = buckets, 7 = packed target
		// (ref null eq); 8 = n, 9 = i, 10 = the index as it arrived (i32).
		w.write(2);
		w.write(5);
		w.writeRefType(true, Type.EQ.code());
		w.write(3);
		w.write(Type.I32);
		int curSlot = 3, promotedSlot = 4, strSlot = 5, bucketsSlot = 6, targetSlot = 7, nSlot = 8, iSlot = 9,
				rawIdxSlot = 10;
		// The walk folds each hop's offset into the index parameter, so the index a
		// packed store has to READ BACK through _arr_get (which walks again) is kept
		// here first -- three instructions instead of a second copy of the packed read.
		get(w, 1);
		set(w, rawIdxSlot);
		emitResolve(w, curSlot, 1);
		// A STRING VIEW over an IMMUTABLE string cannot be written through: promote the
		// target ONCE into a mutable character vector, hang it in the view's data slot,
		// and store into that. Every later access through this view -- and
		// array-displacement's answer -- sees the promoted vector, so the view behaves
		// as a mutable string from here on, exactly as the JVM's _strToCharVec does.
		w.write(Instruction.BLOCK, 0x40);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		emitDataSlot(w, curSlot);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(strSlot);
		emitStringToCharVecCell(w, strSlot, bucketsSlot, nSlot, iSlot);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(promotedSlot);
		// (meta . data) is cur.cdr: replace its cdr with the promoted cell.
		get(w, curSlot);
		consGet(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(promotedSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		// cur = the promoted cell's header, so the store below lands in its buckets.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(promotedSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(curSlot);
		w.write(Instruction.END); // block
		// A view over a PACKED target stores into that target's unboxed slot and
		// answers the value AS STORED -- read back through the same arm the read uses,
		// so a masked or narrowed store answers what the next read will answer.
		w.write(Instruction.BLOCK, 0x40);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.BR_IF, 0);
		emitDataSlot(w, curSlot);
		set(w, targetSlot);
		emitPackedTargetWrite(w, targetSlot, 1, 2, simd);
		get(w, 0);
		get(w, rawIdxSlot);
		call(w, WasmLispCompiler.FUNC_ARR_GET);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // block
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * Builds {@code _arr_dims (dims) -> buckets}: the {@code make-array} DIMENSION
	 * argument -- an integer for the rank-1 shorthand, otherwise a list of sizes of any
	 * rank -- as a fresh buckets array of i31 sizes. Two list walks (count, then copy),
	 * which is why it is a callee: every {@code make-array} shape parses its dimensions
	 * the same way, and the walk used to be spelled at every one of them.
	 * @return the function body (signature {@code ((ref null eq)) -> (ref null eq)},
	 * {@code TYPE_CALLABLE_BASE + 0})
	 */
	static byte[] buildArrDimsBody() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// locals: 1 = cur, 2 = buckets (ref null eq); 3 = n, 4 = idx (i32).
		w.write(2);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		int curSlot = 1, bucketsSlot = 2, nSlot = 3, idxSlot = 4;
		// The rank-1 shorthand: an integer is a one-element shape.
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		get(w, 0);
		i32(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, bucketsSlot);
		w.write(Instruction.ELSE);
		// n = (length dims)
		i32(w, 0);
		set(w, nSlot);
		get(w, 0);
		set(w, curSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		get(w, nSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, nSlot);
		get(w, curSlot);
		consGet(w, 1);
		set(w, curSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// buckets = array.new_default n, then buckets[idx] = (nth idx dims)
		get(w, nSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, bucketsSlot);
		i32(w, 0);
		set(w, idxSlot);
		get(w, 0);
		set(w, curSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, idxSlot);
		get(w, nSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		buckets(w, bucketsSlot);
		get(w, idxSlot);
		get(w, curSlot);
		consGet(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, curSlot);
		consGet(w, 1);
		set(w, curSlot);
		get(w, idxSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.END); // if
		get(w, bucketsSlot);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * Builds {@code _arr_total (buckets) -> i31}: the product of a dimension buckets
	 * array, i.e. the element count of an array of that shape (1 for a rank-0 shape).
	 * Both {@code make-array}'s allocation size and the bound a {@code :displaced-to}
	 * view is checked against are this fold.
	 * @return the function body (signature {@code ((ref null eq)) -> (ref null eq)},
	 * {@code TYPE_CALLABLE_BASE + 0})
	 */
	static byte[] buildArrTotalBody() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// locals: 1 = product, 2 = i (i32).
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		int prodSlot = 1, iSlot = 2;
		i32(w, 1);
		set(w, prodSlot);
		i32(w, 0);
		set(w, iSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, iSlot);
		buckets(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, prodSlot);
		buckets(w, 0);
		get(w, iSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		WasmEmitHelper.castI31GetS(w);
		w.write(Instruction.I32_MUL);
		set(w, prodSlot);
		get(w, iSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, iSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		get(w, prodSlot);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * Builds {@code _arr_fp (fp, buckets) -> i31 | null}: the {@code :fill-pointer}
	 * argument resolved against the shape it is given -- {@code nil} answers null (the
	 * array has none), an integer answers itself after {@code 0 <= fp <= size}, anything
	 * else ({@code t}, the usual spelling) answers the vector size. A fill pointer
	 * requires a rank-1 shape, so a wider one traps, as does an out-of-range integer.
	 * @return the function body (signature
	 * {@code ((ref null eq), (ref null eq)) -> (ref null eq)},
	 * {@code TYPE_CALLABLE_BASE + 1})
	 */
	static byte[] buildArrFpBody() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// local 2 = the resolved value (ref null eq), null until proven otherwise.
		declareOneEqrefLocal(w);
		int valSlot = 2;
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		set(w, valSlot);
		get(w, 0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		// a fill pointer requires a rank-1 array
		buckets(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		// integer: 0 <= fp <= dims[0], else trap
		get(w, 0);
		WasmEmitHelper.castI31GetS(w);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, 0);
		WasmEmitHelper.castI31GetS(w);
		firstDim(w);
		WasmEmitHelper.castI31GetS(w);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, 0);
		set(w, valSlot);
		w.write(Instruction.ELSE);
		// t: the vector size (dims[0] is already an i31)
		firstDim(w);
		set(w, valSlot);
		w.write(Instruction.END);
		w.write(Instruction.END);
		get(w, valSlot);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * Builds {@code _arr_check_rank (arr, given) -> arr}: traps (unreachable) unless
	 * {@code arr}'s actual rank equals {@code given} -- 1 for a string (always a rank-1
	 * character array) or a packed integer vector (rank-1 by construction), else the dims
	 * buckets length (a packed farray's own field 0, or the general array header's car).
	 * {@code aref}/{@code %aset} push the array once and call this before reading any
	 * subscript, so the check runs regardless of which representation arm the value turns
	 * out to be, and the returned reference is what every arm reads from -- the array is
	 * evaluated exactly once at the call site (todo 479; the JVM backend's
	 * {@code _arrayCheckRank}/{@code _fvCheckRank}/{@code _ivCheckRank} chain, in
	 * {@code JvmArrayRuntimeBuilder}, closes the same hole with a message the interpreter
	 * matches -- this backend's internal array-compiler checks are bare traps instead,
	 * like {@code _arr_fp}'s fill-pointer-rank check just above). {@code given} used to
	 * be inlined as a per-site constant compare; moved here (a call, not ~90 bytes of
	 * REF_TEST chain per {@code aref}/{@code %aset} site) once
	 * {@code WasmLispCompilerTest#anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime}
	 * priced the inline form.
	 * @return the function body (signature {@code ((ref null eq), i32) -> (ref null eq)},
	 * {@code TYPE_BIG_SHIFT})
	 */
	static byte[] buildArrCheckRankBody() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write(0); // no extra locals -- every value here lives on the operand stack
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, Type.I32.code());
		i32(w, 1);
		w.write(Instruction.ELSE);
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.IF, Type.I32.code());
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.ELSE);
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.I32_OR);
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I32ARR);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, Type.I32.code());
		i32(w, 1);
		w.write(Instruction.ELSE);
		// general: arr.field0 (the header cons) -> car (the dims buckets) -> its length.
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		consGet(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		get(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, 0);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * Builds {@code _arr_undisplace (header) -> header}: copies a DISPLACED view's
	 * current contents into a buckets array of its own and drops the displacement,
	 * keeping the dims, the fill pointer and the adjustable flag. A header whose data
	 * slot is already the buckets array is returned untouched.
	 *
	 * <p>
	 * {@code vector-push-extend} calls it when a full fill-pointered view has to grow:
	 * the growth then extends storage of its own instead of running off the end of the
	 * target's, and {@code array-displacement} answers nil from there on -- which is what
	 * SBCL 2.2.9 does. The meta OFFSET word doubles as the element-type marker, so the
	 * resolved target's marker is copied into it (1, the character-vector marker, when
	 * the chain ends on a string), exactly as {@code %array-adopt-element-type} copies
	 * it: a grown string view is still a string, and a grown view over a
	 * {@code (unsigned-byte 8)} array still answers {@code (unsigned-byte 8)} -- the same
	 * marker the view itself answered, since a view's element type IS its target's.
	 * @return the function body (signature {@code ((ref null eq)) -> (ref null eq)},
	 * {@code TYPE_CALLABLE_BASE + 0})
	 */
	static byte[] buildArrUndisplaceBody(boolean simd) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// locals: 1 = cur, 2 = newData (ref null eq); 3 = n, 4 = i, 5 = marker (i32).
		w.write(2);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(3);
		w.write(Type.I32);
		int curSlot = 1, newDataSlot = 2, nSlot = 3, iSlot = 4, markerSlot = 5;
		// An ordinary array's data slot is the buckets array: nothing to do. Every
		// OTHER data slot is a displacement -- a target cell, a string, or a packed
		// vector.
		emitDataSlot(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.IF, 0x40);
		get(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// n = the VIEW's own element count (the product of its dims).
		get(w, 0);
		consGet(w, 0);
		call(w, WasmLispCompiler.FUNC_ARR_TOTAL);
		WasmEmitHelper.castI31GetS(w);
		set(w, nSlot);
		// Walk to the end of the chain to decide the marker the view's own offset word
		// has to become: the chain end's own marker, or 1 (the character-vector marker)
		// when the chain ends on a string. That is the marker array-element-type read
		// for the VIEW while it was still a view -- a view owns no storage, so its
		// elements are the target's and its element type is the target's, resolved the
		// same way through the same chain (WasmArrayCompiler's
		// emitRememberedElementType). Copying it here is therefore not a CHANGE of an
		// existing array's element type (which .todo/619's invariant forbids) but the
		// only way to keep the answer: once the displacement drops there is no chain
		// left to walk. The JVM carries the same fact in its own header (7 -> 4 for a
		// string view, else the chain end's slot 4 into the freed offset slot).
		get(w, 0);
		set(w, curSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		set(w, curSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, Type.I32.code());
		i32(w, 1);
		w.write(Instruction.ELSE);
		// A PACKED chain end's element type IS its representation, so the marker is
		// read off the target's width rather than out of a meta word it does not have
		// (the view's own word still holds its OFFSET at this point).
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, Type.I32.code());
		emitDataSlot(w, curSlot);
		set(w, newDataSlot);
		emitPackedTargetMarker(w, newDataSlot, simd);
		w.write(Instruction.ELSE);
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 0);
		consGet(w, 1);
		consGet(w, 1);
		WasmEmitHelper.castI31GetS(w);
		w.write(Instruction.END);
		w.write(Instruction.END);
		set(w, markerSlot);
		// newData[i] = _arr_get(header, i) -- read through the chain BEFORE the data slot
		// is replaced, since that is what the reads resolve against.
		get(w, nSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, newDataSlot);
		i32(w, 0);
		set(w, iSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, iSlot);
		get(w, nSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		buckets(w, newDataSlot);
		get(w, iSlot);
		get(w, 0);
		get(w, iSlot);
		call(w, WasmLispCompiler.FUNC_ARR_GET);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, iSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, iSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// (meta . data).cdr = newData, and the offset word becomes the marker.
		get(w, 0);
		consGet(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		get(w, newDataSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		get(w, 0);
		consGet(w, 1);
		consGet(w, 0);
		consGet(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		get(w, markerSlot);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		get(w, 0);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	// Pushes buckets[0] of local 1: the size of the rank-1 shape, as an i31.
	private static void firstDim(WasmWriter w) {
		buckets(w, 1);
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// Pushes the local as a TYPE_HASH_BUCKETS reference.
	private static void buckets(WasmWriter w, int slot) {
		get(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void set(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	// One local group of count (ref null eq).
	private static void declareEqrefLocals(WasmWriter w, int count) {
		w.write(1);
		w.write(count);
		w.writeRefType(true, Type.EQ.code());
	}

	// One local group of one (ref null eq).
	private static void declareOneEqrefLocal(WasmWriter w) {
		declareEqrefLocals(w, 1);
	}

	// Pushes the data slot (cur.cdr.cdr) of the header in curSlot: the element buckets
	// of an ordinary array, the target CELL of an array view, or the STRING a string
	// view aliases.
	private static void emitDataSlot(WasmWriter w, int curSlot) {
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 1);
	}

	// Walks the displacement chain from local 0 (the header) with the flat index in
	// flatSlot (an i32 parameter, updated in place). curSlot is a scratch (ref null eq)
	// local and holds the FINAL header on exit; the stack is left empty, so the caller
	// reads the data slot itself (it is the buckets array, or the string a string view
	// aliases). The inline original is WasmArrayCompiler's emitResolveDataAndIndex,
	// which this replaces; the only difference is that the index stays a raw i32 here
	// rather than being re-boxed as an i31 each round, which a parameter lets it do.
	private static void emitResolve(WasmWriter w, int curSlot, int flatSlot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(curSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// exit when the data slot is not a cell (it is the buckets array, or a string)
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		emitAddMetaOffset(w, curSlot, flatSlot);
		// cur = the target cell's header
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(curSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// A STRING or PACKED target ends the walk WITHOUT a hop, so this view's own
		// offset has not been folded in yet. (An ordinary array's offset word is 0, and
		// a character vector's is the marker 1, so only this arm may add it -- which is
		// exactly "the data slot is not the buckets array".)
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitAddMetaOffset(w, curSlot, flatSlot);
		w.write(Instruction.END);
	}

	// Pushes the boxed element the PACKED displacement target in targetSlot holds at the
	// raw i32 flat index in flatSlot: a packed integer vector's element widened UNSIGNED
	// and boxed through _int_new, a packed float array's widened to f64 and boxed as a
	// TYPE_FLOAT. This is the same element semantics the site-level packed arms
	// (WasmArrayCompiler's emitPackedIntRead / emitPackedReadF64) give for a direct
	// access -- the view just reaches the storage through one more indirection.
	private static void emitPackedTargetRead(WasmWriter w, int targetSlot, int flatSlot, boolean simd) {
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		emitFarrayReadF64(w, targetSlot, flatSlot, simd);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);
		emitIntVectorRead(w, targetSlot, flatSlot);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.END);
	}

	// Pushes the f64 the packed float array in targetSlot holds at flatSlot, promoting a
	// single-float element. Under --simd the data is a TYPE_VBLOCK read through _v_get.
	private static void emitFarrayReadF64(WasmWriter w, int targetSlot, int flatSlot, boolean simd) {
		if (simd) {
			farrayData(w, targetSlot);
			get(w, flatSlot);
			call(w, WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_GET);
			return;
		}
		farrayData(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		w.write(Instruction.IF);
		w.write(Type.F64);
		farrayData(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		get(w, flatSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
		w.write(Instruction.F64_PROMOTE_F32);
		w.write(Instruction.ELSE);
		farrayData(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
		get(w, flatSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
		w.write(Instruction.END);
	}

	// Pushes the meta ELEMENT-TYPE MARKER of the PACKED value in targetSlot: the marker
	// WasmArrayCompiler.elementTypeMarker gives for the width it holds. Used where a
	// packed chain end's element type has to be recorded in a word (the un-displace) or
	// answered from one (array-element-type).
	private static void emitPackedTargetMarker(WasmWriter w, int targetSlot, boolean simd) {
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.IF, Type.I32.code());
		i32(w, WasmArrayCompiler.elementTypeMarker(ArrayElementTypes.UNSIGNED_BYTE_8));
		w.write(Instruction.ELSE);
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.IF, Type.I32.code());
		i32(w, WasmArrayCompiler.elementTypeMarker(ArrayElementTypes.UNSIGNED_BYTE_16));
		w.write(Instruction.ELSE);
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I32ARR);
		w.write(Instruction.IF, Type.I32.code());
		i32(w, WasmArrayCompiler.elementTypeMarker(ArrayElementTypes.UNSIGNED_BYTE_32));
		w.write(Instruction.ELSE);
		// A packed float array: single when the data array is a TYPE_F32ARR (--simd:
		// when the vblock's kind field is 1), else double.
		if (simd) {
			farrayData(w, targetSlot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_VBLOCK);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
			w.writeUnsignedLeb128(1);
		}
		else {
			farrayData(w, targetSlot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		}
		w.write(Instruction.IF, Type.I32.code());
		i32(w, WasmArrayCompiler.elementTypeMarker(ArrayElementTypes.SINGLE_FLOAT));
		w.write(Instruction.ELSE);
		i32(w, WasmArrayCompiler.elementTypeMarker(ArrayElementTypes.DOUBLE_FLOAT));
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Pushes the data field of the TYPE_FARRAY in targetSlot.
	private static void farrayData(WasmWriter w, int targetSlot) {
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeUnsignedLeb128(1);
	}

	// Pushes the element the packed integer vector in targetSlot holds at flatSlot as an
	// UNSIGNED i64 (array.get_u for the sub-word widths, an unsigned extend for i32).
	private static void emitIntVectorRead(WasmWriter w, int targetSlot, int flatSlot) {
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.IF);
		w.write(Type.I64);
		emitIntArrGet(w, targetSlot, flatSlot, WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.ELSE);
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.IF);
		w.write(Type.I64);
		emitIntArrGet(w, targetSlot, flatSlot, WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.ELSE);
		emitIntArrGet(w, targetSlot, flatSlot, WasmLispCompiler.TYPE_I32ARR);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	private static void emitIntArrGet(WasmWriter w, int targetSlot, int flatSlot, int type) {
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(type);
		get(w, flatSlot);
		w.write(Instruction.GC_PREFIX,
				type == WasmLispCompiler.TYPE_I32ARR ? Instruction.ARRAY_GET : Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(type);
		w.write(Instruction.I64_EXTEND_U_I32);
	}

	// Stores the value in valSlot into the PACKED displacement target in targetSlot at
	// the raw i32 flat index in flatSlot, leaving nothing: an integer vector truncates
	// to its element width through the shared _iv_set, a float array narrows to its
	// backing width. The caller reads the element back for the value AS STORED, which is
	// what every other packed store answers.
	private static void emitPackedTargetWrite(WasmWriter w, int targetSlot, int flatSlot, int valSlot, boolean simd) {
		get(w, targetSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.IF, 0x40);
		if (simd) {
			farrayData(w, targetSlot);
			get(w, flatSlot);
			get(w, valSlot);
			call(w, WasmLispCompiler.FUNC_AS_F64);
			call(w, WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_SET);
			w.write(Instruction.DROP);
		}
		else {
			farrayData(w, targetSlot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
			w.write(Instruction.IF, 0x40);
			farrayData(w, targetSlot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
			get(w, flatSlot);
			get(w, valSlot);
			call(w, WasmLispCompiler.FUNC_AS_F64);
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
			w.write(Instruction.ELSE);
			farrayData(w, targetSlot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
			get(w, flatSlot);
			get(w, valSlot);
			call(w, WasmLispCompiler.FUNC_AS_F64);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
			w.write(Instruction.END);
		}
		w.write(Instruction.ELSE);
		get(w, targetSlot);
		get(w, flatSlot);
		WasmArrayCompiler.emitUnboxIntForStore(w, valSlot);
		call(w, WasmLispCompiler.FUNC_IV_SET);
		w.write(Instruction.END);
	}

	// flat += the meta offset (cur.cdr.car.cdr.cdr) of the header in curSlot.
	private static void emitAddMetaOffset(WasmWriter w, int curSlot, int flatSlot) {
		get(w, flatSlot);
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 0);
		consGet(w, 1);
		consGet(w, 1);
		WasmEmitHelper.castI31GetS(w);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(flatSlot);
	}

	// Builds a mutable character vector CELL holding the characters of the string in
	// strSlot, and leaves it on the stack: the general array shape whose meta offset is
	// the character-vector marker i31 1, its dimension the character count, and its
	// buckets one TYPE_CHAR per character. This is what a write through a string view
	// over an IMMUTABLE string promotes the target to (the JVM twin is
	// JvmArrayRuntimeBuilder's _strToCharVec), and what _str_to_cv
	// (WasmStringRuntimeBuilder.buildStrToCvBody) wraps as a callable function.
	static void emitStringToCharVecCell(WasmWriter w, int strSlot, int bucketsSlot, int nSlot, int iSlot) {
		get(w, strSlot);
		WasmEmitHelper.emitStrCharCountCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(nSlot);
		get(w, nSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(bucketsSlot);
		i32(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(iSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, iSlot);
		get(w, nSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, bucketsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, iSlot);
		get(w, strSlot);
		get(w, iSlot);
		WasmEmitHelper.emitStrCharAtCall(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, iSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(iSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		emitFreshCharVecCellFromBuckets(w, bucketsSlot, nSlot);
	}

	// Assembles a fresh character-vector CELL around a pre-filled buckets array of n
	// TYPE_CHAR elements and leaves it on the stack: dims = [n], meta =
	// (null . (null . i31 1)) -- no fill pointer, not adjustable, the character-vector
	// marker in the offset word -- then (dims . (meta . buckets)) boxed in a TYPE_CELL.
	static void emitFreshCharVecCellFromBuckets(WasmWriter w, int bucketsSlot, int nSlot) {
		// dims = [n]
		get(w, nSlot);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		i32(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		// meta = (null . (null . i31 1))
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		i32(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		// (meta . buckets), then (dims . that), then the cell
		get(w, bucketsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	// call <index> -- a fixed runtime helper (indices below FX_FUNC_LAST never shift).
	private static void call(WasmWriter w, int index) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(index);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void get(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	// Casts the (ref null eq) on the stack to TYPE_CONS and reads car (0) or cdr (1).
	private static void consGet(WasmWriter w, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
	}

}
