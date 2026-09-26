package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispNames;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the WASM bytecode for the runtime {@code eval} interpreter and its supporting
 * helpers ({@code _lookup}, {@code _env_lookup}, {@code _apply}).
 *
 * <p>
 * The interpreter is a small tree walker that runs at runtime inside the generated
 * module. It implements a lexical environment: an environment is an association list of
 * bindings, each binding a {@code cons(nameSymbol, value)} cell, with the empty
 * environment being {@code ref.null eq}. Variable references walk the environment
 * comparing the symbol's string-table offset; because the string table deduplicates, a
 * quoted symbol such as {@code 'x} shares its offset with the binding name introduced by
 * {@code let}/{@code
 * lambda}, so lookup is a plain {@code i32} offset comparison.
 *
 * <p>
 * Interpreted closures (created by {@code lambda} at runtime) reuse the compiled closure
 * struct {@code {i32 funcId, (ref null eq) env}} with the sentinel {@code funcId == -1};
 * its {@code env} field holds {@code cons(lambdaTail, capturedEnv)} where
 * {@code lambdaTail = ((params) body...)}. Compiled functions (built-in wrappers and user
 * defuns) are applied through the arity dispatch functions.
 */
final class WasmEvalRuntimeBuilder {

	private WasmEvalRuntimeBuilder() {
	}

	/**
	 * String-table offsets of the symbols treated specially by {@code _eval}, keyed by
	 * symbol name. Built with {@link #builder()} so that each entry is named at the call
	 * site rather than positional.
	 */
	static final class SpecialFormOffsets {

		private final Map<String, Integer> offsets;

		private SpecialFormOffsets(Map<String, Integer> offsets) {
			this.offsets = offsets;
		}

		/**
		 * Returns the string-table offset registered for the given symbol name.
		 * @param symbol the symbol name
		 * @return its string-table offset
		 */
		int of(String symbol) {
			Integer offset = this.offsets.get(symbol);
			if (offset == null) {
				throw new IllegalStateException("No offset registered for special-form symbol: " + symbol);
			}
			return offset;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private final Map<String, Integer> offsets = new HashMap<>();

			/**
			 * Interns {@code symbol} in the string table and records its offset.
			 * @param stringTable the string table to intern into
			 * @param symbol the symbol name
			 * @return this builder for chaining
			 */
			Builder add(WasmLispCompiler.StringTable stringTable, String symbol) {
				this.offsets.put(symbol, stringTable.addString(symbol).offset());
				return this;
			}

			SpecialFormOffsets build() {
				return new SpecialFormOffsets(Map.copyOf(this.offsets));
			}

		}

	}

	// === low-level emit helpers ===

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void refTest(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(heapType);
	}

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
	}

	private static void structSet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
	}

	private static void structNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(type);
	}

	private static void emitNull(WasmWriter w) {
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
	}

	/** Boxes the i32 on the stack into an i31ref (the integer value representation). */
	private static void i31New(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/** Unboxes the i31ref on the stack into a signed i32. */
	private static void i31GetS(WasmWriter w) {
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	/** Emits {@code (car (ref.cast cons (local.get slot)))} onto the stack. */
	private static void emitCarOf(WasmWriter w, int slot) {
		getLocal(w, slot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
	}

	/** Emits {@code (cdr (ref.cast cons (local.get slot)))} onto the stack. */
	private static void emitCdrOf(WasmWriter w, int slot) {
		getLocal(w, slot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
	}

	/** Emits {@code (eval (car (local.get slot)) (local.get envSlot))} onto the stack. */
	private static void emitEvalCar(WasmWriter w, int slot, int envSlot) {
		emitCarOf(w, slot);
		getLocal(w, envSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
	}

	/** Emits {@code global.get $genv} (the top-level eval environment). */
	private static void emitGetGlobalEnv(WasmWriter w) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
	}

	/** Emits {@code global.get $fenv} (the runtime function namespace). */
	private static void emitGetGlobalFenv(WasmWriter w) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
	}

	/**
	 * Emits code that resolves a function name offset against the function namespace and
	 * returns the function value: a runtime {@code defun} binding in {@code $fenv} first,
	 * then the compiled registry (wrapped as a closure {@code {funcId, null}}), and nil
	 * when undefined. Every path ends in {@code return}.
	 */
	private static void emitFunctionLookupReturn(WasmWriter w, int offSlot, int tmpSlot, int addrSlot,
			boolean identityHash) {
		// runtime defun binding in $fenv?
		getLocal(w, offSlot);
		emitGetGlobalFenv(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		setLocal(w, tmpSlot);
		getLocal(w, tmpSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitCdrOf(w, tmpSlot);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// compiled registry?
		getLocal(w, offSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		setLocal(w, addrSlot);
		getLocal(w, addrSlot);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, addrSlot);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		emitNull(w);
		WasmEmitHelper.emitNewClosure(w, identityHash);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitNull(w);
		w.write(Instruction.RETURN);
	}

	/**
	 * Emits code that installs a function binding into the {@code $fenv} function
	 * namespace: an existing binding's value cell is mutated, otherwise a new binding is
	 * prepended. Reads the name (a string struct) from {@code nameSlot} and the value
	 * from {@code valueSlot}; clobbers {@code tmpSlot} and {@code offScratch}.
	 */
	private static void emitStoreFunctionBinding(WasmWriter w, int nameSlot, int valueSlot, int tmpSlot, int offScratch,
			boolean identityHash) {
		// off = name.offset
		getLocal(w, nameSlot);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, offScratch);
		// existing = _env_lookup(off, $fenv)
		getLocal(w, offScratch);
		emitGetGlobalFenv(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		setLocal(w, tmpSlot);
		getLocal(w, tmpSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		// create: $fenv = cons(cons(name, value), $fenv)
		getLocal(w, nameSlot);
		getLocal(w, valueSlot);
		WasmEmitHelper.emitNewCons(w, identityHash);
		emitGetGlobalFenv(w);
		WasmEmitHelper.emitNewCons(w, identityHash);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.ELSE);
		// mutate: binding.cdr = value
		getLocal(w, tmpSlot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, valueSlot);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.END);
	}

	/**
	 * Emits {@code binding = _env_lookup(off, $genv); if (binding != null) return
	 * cdr(binding);} using {@code tmpSlot} for the binding. Used to consult the global
	 * environment after a lexical lookup misses.
	 */
	private static void emitGlobalLookupReturn(WasmWriter w, int offSlot, int tmpSlot) {
		getLocal(w, offSlot);
		emitGetGlobalEnv(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		setLocal(w, tmpSlot);
		getLocal(w, tmpSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitCdrOf(w, tmpSlot);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// === stubs (emitted when the program does not call eval, to keep indices stable) ===

	/** {@code _lookup} stub: {@code (i32) -> i32}, always -1. */
	static byte[] buildLookupStub() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		i32(w, -1);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** {@code _env_lookup} stub: {@code (i32, ref) -> ref}, always null. */
	static byte[] buildEnvLookupStub() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		emitNull(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** {@code _eval} stub: {@code (ref, ref) -> ref}, returns its first argument. */
	static byte[] buildEvalStub() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		getLocal(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** {@code _apply} stub: {@code (ref, ref) -> ref}, always null. */
	static byte[] buildApplyStub() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		emitNull(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * {@code _store} stub: {@code (ref, ref, ref) -> ref}, returns its value argument.
	 */
	static byte[] buildStoreStub() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		getLocal(w, 1);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// === _lookup: registry name-offset -> record address ===

	/**
	 * Builds {@code _lookup}: {@code (i32 nameOffset) -> i32}. Linearly scans the
	 * registry (records of 12 bytes: nameOffset, funcId, arity) for a record whose name
	 * offset equals the argument, returning the record's base address, or -1 if not
	 * found.
	 * @param registryBase absolute memory address of the first registry record
	 * @param registryCount number of registry records
	 * @return the encoded function body
	 */
	static byte[] buildLookupBody(int registryBase, int registryCount) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: slot1 = i (i32), slot2 = addr (i32); param0 = nameOffset
		w.write(1);
		w.write(2);
		w.write(Type.I32);

		final int OFF = 0, I = 1, ADDR = 2;

		i32(w, 0);
		setLocal(w, I);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		// if i >= count: break
		getLocal(w, I);
		i32(w, registryCount);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);

		// addr = registryBase + i * 12
		i32(w, registryBase);
		getLocal(w, I);
		i32(w, 12);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(ADDR);
		// load name offset at addr+0, compare with param
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, OFF);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, ADDR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// i++
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		i32(w, -1);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// === _env_lookup: (offset, env) -> binding cons or null ===

	/**
	 * Builds {@code _env_lookup}: {@code (i32 off, (ref null eq) env) -> (ref null eq)}.
	 * Walks the environment association list and returns the first binding cell
	 * {@code cons(name, value)} whose name symbol has string offset {@code off}, or
	 * {@code null} if the symbol is unbound.
	 * @return the encoded function body
	 */
	static byte[] buildEnvLookupBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// params: 0 = off (i32), 1 = env (ref); locals: 2 = pair, 3 = name
		w.write(1);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());

		final int OFF = 0, ENV = 1, PAIR = 2, NAME = 3;

		w.write(Instruction.LOOP, 0x40);

		// if env is null: return null
		getLocal(w, ENV);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// pair = car(env); name = car(pair)
		emitCarOf(w, ENV);
		setLocal(w, PAIR);
		emitCarOf(w, PAIR);
		setLocal(w, NAME);

		// if name is a string symbol with offset == off: return pair
		getLocal(w, NAME);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, NAME);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		getLocal(w, OFF);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, PAIR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// env = cdr(env); continue
		emitCdrOf(w, ENV);
		setLocal(w, ENV);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop

		emitNull(w); // unreachable, satisfies the result type
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// === _eval: (form, env) -> value ===

	/**
	 * Builds {@code _eval}: {@code ((ref null eq) form, (ref null eq) env) -> (ref null
	 * eq)}, the runtime tree-walking interpreter.
	 *
	 * <p>
	 * Supported: self-evaluating atoms (integers, floats, strings, closures, nil);
	 * variable references resolved against the lexical environment then the global
	 * variable environment (Lisp-2: never the function namespace; an unbound symbol
	 * evaluates to itself); the special forms {@code quote}, {@code if}, {@code progn},
	 * {@code let}, {@code lambda}, {@code defun}, {@code function} ({@code #'}),
	 * {@code symbol-function}, {@code cond}, {@code and}, {@code or}, {@code when},
	 * {@code unless}, {@code while}, {@code dotimes}, {@code setq}, {@code eval}
	 * (nested), {@code funcall} and {@code list}; variadic
	 * {@code + - * /}; chained {@code = < > <= >= /=}; {@code car}/{@code cdr}
	 * compositions such as {@code cadr}; and application of any registered function
	 * (built-in wrappers and user defuns) as well as interpreted closures produced by
	 * {@code lambda}.
	 * @param off the string-table offsets of the special-form symbols
	 * @param counts what the arms that check their own argument count report through
	 * @return the encoded function body
	 */
	static byte[] buildEvalBody(SpecialFormOffsets off, CountChecks counts, boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// params: 0 = VAL (form), 1 = ENV
		// ref locals: 2..13, i32 locals: 14..19
		w.write(2);
		w.write(12);
		w.writeRefType(true, Type.EQ.code());
		w.write(6);
		w.write(Type.I32);

		final int VAL = 0, ENV = 1, REST = 2, TMP = 3, OP = 4, ARGHEAD = 5, ARGTAIL = 6, NEWCELL = 7, ACC = 8, FN = 9,
				BODY = 10, BINDCUR = 11, ELEM = 12;
		final int OFF = 14, LEN = 15, ADDR = 16, ARITY = 17, IDX = 18, CH = 19;

		// --- 1. nil is self-evaluating ---
		getLocal(w, VAL);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, VAL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// --- 2. integers (i31 / boxed i64 / limb), ratios, floats and closures are
		// self-evaluating ---
		for (int heapType : new int[] { Type.I31.code(), WasmLispCompiler.TYPE_BIGNUM, WasmLispCompiler.TYPE_BIGINT,
				WasmLispCompiler.TYPE_RATIO, WasmLispCompiler.TYPE_FLOAT, WasmLispCompiler.TYPE_CLOSURE }) {
			getLocal(w, VAL);
			refTest(w, heapType);
			w.write(Instruction.IF, 0x40);
			getLocal(w, VAL);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}

		// --- 3. strings: string literal (self-eval) or symbol (variable reference) ---
		getLocal(w, VAL);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, VAL);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, OFF);
		// A leading quote marks a string literal (self-evaluating); else it is a symbol
		// (variable reference). OFF stays the field-0 id for _env_lookup.
		strByteAt(w, VAL, () -> i32(w, 0));
		i32(w, 0x22);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, VAL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// symbol: look up in the lexical environment
		getLocal(w, OFF);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		setLocal(w, TMP);
		getLocal(w, TMP);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitCdrOf(w, TMP);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// not bound lexically: try the global environment. Lisp-2: a bare symbol
		// resolves the variable namespace only, never the function registry. An
		// unbound symbol evaluates to itself.
		emitGlobalLookupReturn(w, OFF, TMP);
		getLocal(w, VAL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // end string-if

		// --- 4. cons: special form or application ---
		emitCarOf(w, VAL);
		setLocal(w, OP);
		emitCdrOf(w, VAL);
		setLocal(w, REST);

		getLocal(w, OP);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, OP);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, OFF);
		getLocal(w, OP);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
		setLocal(w, LEN);

		// ---- quote ----
		openSpecial(w, OFF, off.of(LispNames.QUOTE));
		emitCarOf(w, REST);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- if ----
		openSpecial(w, OFF, off.of(LispNames.IF));
		emitEvalCar(w, REST, ENV);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		getLocal(w, REST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitEvalCar(w, REST, ENV);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		emitEvalCar(w, REST, ENV);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// ---- progn ----
		openSpecial(w, OFF, off.of(LispNames.PROGN));
		emitPrognLoop(w, REST, ENV, TMP);
		getLocal(w, TMP);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- let ----
		openSpecial(w, OFF, off.of(LispNames.LET));
		emitCdrOf(w, REST);
		setLocal(w, BODY);
		emitCarOf(w, REST);
		setLocal(w, BINDCUR);
		getLocal(w, ENV);
		setLocal(w, ELEM); // ELEM = newEnv accumulator
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, BINDCUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		emitCarOf(w, BINDCUR);
		setLocal(w, TMP);
		emitCdrOf(w, TMP);
		setLocal(w, NEWCELL);
		emitEvalCar(w, NEWCELL, ENV);
		setLocal(w, NEWCELL);
		emitCarOf(w, TMP);
		getLocal(w, NEWCELL);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, TMP);
		getLocal(w, TMP);
		getLocal(w, ELEM);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, ELEM);
		emitCdrOf(w, BINDCUR);
		setLocal(w, BINDCUR);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, BODY);
		setLocal(w, REST);
		emitPrognLoop(w, REST, ELEM, TMP);
		getLocal(w, TMP);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- lambda ----
		openSpecial(w, OFF, off.of(LispNames.LAMBDA));
		getLocal(w, REST);
		getLocal(w, ENV);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, TMP);
		i32(w, -1);
		getLocal(w, TMP);
		WasmEmitHelper.emitNewClosure(w, identityHash);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- defun: (defun name (params) body...) builds a closure and installs it
		// into the $fenv function namespace so loaded files can define functions ----
		openSpecial(w, OFF, off.of(LispNames.DEFUN));
		emitCarOf(w, REST); // name symbol
		setLocal(w, ACC);
		// lambdaForm = cons("lambda", cdr(REST))
		i32(w, off.of(LispNames.LAMBDA));
		i32(w, 6); // "lambda".length()
		WasmEmitHelper.emitStrBuildCall(w);
		emitCdrOf(w, REST);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, TMP);
		// value = eval(lambdaForm, ENV)
		getLocal(w, TMP);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
		setLocal(w, NEWCELL);
		// install into the function namespace (Lisp-2): $fenv, not $genv
		emitStoreFunctionBinding(w, ACC, NEWCELL, TMP, IDX, identityHash);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- function: (function name) / #'name resolves the function namespace;
		// (function (lambda ...)) evaluates to a closure ----
		openSpecial(w, OFF, off.of(LispNames.FUNCTION));
		emitCarOf(w, REST);
		setLocal(w, ACC); // designator (unevaluated)
		getLocal(w, ACC);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, ACC);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, IDX);
		emitFunctionLookupReturn(w, IDX, TMP, ADDR, identityHash);
		w.write(Instruction.END);
		// non-symbol designator (a lambda form): evaluate it
		getLocal(w, ACC);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- symbol-function: like function but the argument is evaluated ----
		openSpecial(w, OFF, off.of(LispNames.SYMBOL_FUNCTION));
		emitEvalCar(w, REST, ENV);
		setLocal(w, ACC);
		getLocal(w, ACC);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, ACC);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, IDX);
		emitFunctionLookupReturn(w, IDX, TMP, ADDR, identityHash);
		w.write(Instruction.END);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- cond ----
		openSpecial(w, OFF, off.of(LispNames.COND));
		getLocal(w, REST);
		setLocal(w, BINDCUR);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, BINDCUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitCarOf(w, BINDCUR);
		setLocal(w, TMP); // clause
		emitEvalCar(w, TMP, ENV);
		setLocal(w, ACC); // test value
		getLocal(w, ACC);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitCdrOf(w, TMP);
		setLocal(w, BODY);
		getLocal(w, BODY);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		getLocal(w, BODY);
		setLocal(w, REST);
		emitPrognLoop(w, REST, ENV, TMP);
		getLocal(w, TMP);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitCdrOf(w, BINDCUR);
		setLocal(w, BINDCUR);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- and ----
		openSpecial(w, OFF, off.of(LispNames.AND));
		getLocal(w, REST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		i32(w, off.of("T"));
		i32(w, 1);
		WasmEmitHelper.emitStrBuildCall(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		getLocal(w, REST);
		setLocal(w, BINDCUR);
		emitNull(w);
		setLocal(w, ACC);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, BINDCUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		emitEvalCar(w, BINDCUR, ENV);
		setLocal(w, ACC);
		getLocal(w, ACC);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitCdrOf(w, BINDCUR);
		setLocal(w, BINDCUR);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- or ----
		openSpecial(w, OFF, off.of(LispNames.OR));
		getLocal(w, REST);
		setLocal(w, BINDCUR);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, BINDCUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitEvalCar(w, BINDCUR, ENV);
		setLocal(w, ACC);
		getLocal(w, ACC);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitCdrOf(w, BINDCUR);
		setLocal(w, BINDCUR);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- when ----
		openSpecial(w, OFF, off.of(LispNames.WHEN));
		emitEvalCar(w, REST, ENV);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		emitPrognLoop(w, REST, ENV, TMP);
		getLocal(w, TMP);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- unless ----
		openSpecial(w, OFF, off.of(LispNames.UNLESS));
		emitEvalCar(w, REST, ENV);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		emitPrognLoop(w, REST, ENV, TMP);
		getLocal(w, TMP);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- while: (while test body...) -> evaluate body while test is non-nil ----
		openSpecial(w, OFF, off.of(LispNames.WHILE));
		emitCarOf(w, REST);
		setLocal(w, FN); // FN = test form
		emitCdrOf(w, REST);
		setLocal(w, BODY); // BODY = body list
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, FN);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1); // test nil -> exit
		getLocal(w, BODY);
		setLocal(w, REST);
		emitPrognLoop(w, REST, ENV, TMP);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- dotimes: (dotimes (var count result?) body...) ----
		openSpecial(w, OFF, off.of(LispNames.DOTIMES));
		emitCarOf(w, REST);
		setLocal(w, TMP); // TMP = (var count result?)
		emitCarOf(w, TMP);
		setLocal(w, BINDCUR); // BINDCUR = loop variable symbol
		emitCdrOf(w, TMP);
		setLocal(w, ACC); // ACC = (count result?)
		emitEvalCar(w, ACC, ENV); // evaluate the count form once
		i31GetS(w);
		setLocal(w, ARITY); // ARITY = count limit (i32)
		emitCdrOf(w, ACC);
		setLocal(w, ACC); // ACC = (result?) or nil
		getLocal(w, ACC);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		emitNull(w);
		w.write(Instruction.ELSE);
		emitCarOf(w, ACC);
		w.write(Instruction.END);
		setLocal(w, FN); // FN = result form (or nil when absent)
		emitCdrOf(w, REST);
		setLocal(w, BODY); // BODY = body list
		// bindCell = cons(var, i31(0)); newEnv = cons(bindCell, env)
		getLocal(w, BINDCUR);
		i32(w, 0);
		i31New(w);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, NEWCELL); // NEWCELL = mutable binding cell
		getLocal(w, NEWCELL);
		getLocal(w, ENV);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, ELEM); // ELEM = extended environment
		i32(w, 0);
		setLocal(w, IDX); // IDX = loop counter
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, IDX);
		getLocal(w, ARITY);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1); // i >= count -> exit
		getLocal(w, NEWCELL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, IDX);
		i31New(w);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1); // bind var = i
		getLocal(w, BODY);
		setLocal(w, REST);
		emitPrognLoop(w, REST, ELEM, TMP);
		getLocal(w, IDX);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, IDX);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// var = count for the result form
		getLocal(w, NEWCELL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, ARITY);
		i31New(w);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		getLocal(w, FN);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		emitNull(w);
		w.write(Instruction.ELSE);
		getLocal(w, FN);
		getLocal(w, ELEM);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- setq: assign each name = value via _store ----
		openSpecial(w, OFF, off.of(LispNames.SETQ));
		getLocal(w, REST);
		setLocal(w, BINDCUR);
		emitNull(w);
		setLocal(w, ACC);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, BINDCUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		// place = car(bindcur) (a symbol); value = eval(cadr(bindcur))
		emitCarOf(w, BINDCUR);
		emitCdrOf(w, BINDCUR);
		setLocal(w, NEWCELL);
		emitEvalCar(w, NEWCELL, ENV);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STORE);
		setLocal(w, ACC);
		emitCdrOf(w, BINDCUR);
		setLocal(w, BINDCUR);
		emitCdrOf(w, BINDCUR);
		setLocal(w, BINDCUR);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- setf: store value into a place ----
		openSpecial(w, OFF, off.of(LispNames.SETF));
		emitCarOf(w, REST); // place form
		emitCdrOf(w, REST);
		setLocal(w, NEWCELL);
		emitEvalCar(w, NEWCELL, ENV); // value
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STORE);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- push: (push item place) -> store cons(item, place) into place ----
		openSpecial(w, OFF, off.of(LispNames.PUSH));
		emitEvalCar(w, REST, ENV); // item
		setLocal(w, ACC);
		emitCdrOf(w, REST);
		setLocal(w, REST); // (place)
		emitCarOf(w, REST);
		setLocal(w, BODY); // place form
		// newval = cons(item, eval(place))
		getLocal(w, ACC);
		emitEvalCar(w, REST, ENV);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, ACC);
		// _store(place, newval, env)
		getLocal(w, BODY);
		getLocal(w, ACC);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STORE);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- pop: (pop place) -> result=car(place); store cdr(place); return result
		// ----
		openSpecial(w, OFF, off.of(LispNames.POP));
		emitCarOf(w, REST);
		setLocal(w, BODY); // place form
		emitEvalCar(w, REST, ENV);
		setLocal(w, ELEM); // current list value
		// result = (car list) if a cons, else nil
		getLocal(w, ELEM);
		refTest(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.IF, 0x40);
		emitCarOf(w, ELEM);
		setLocal(w, ACC);
		emitCdrOf(w, ELEM);
		setLocal(w, TMP); // new value = cdr(list)
		w.write(Instruction.ELSE);
		emitNull(w);
		setLocal(w, ACC);
		emitNull(w);
		setLocal(w, TMP);
		w.write(Instruction.END);
		// _store(place, cdr, env), discard; return saved car
		getLocal(w, BODY);
		getLocal(w, TMP);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STORE);
		w.write(Instruction.DROP);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- eval (nested): evaluate the argument, then evaluate its result ----
		// No wrapper backs eval, so the arm reports a wrong count itself.
		openSpecial(w, OFF, off.of(LispNames.EVAL));
		emitArgCountIs(w, REST, TMP, 1);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitCountReport(w, counts, LispNames.EVAL, REST);
		w.write(Instruction.END);
		emitEvalCar(w, REST, ENV);
		emitNull(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- funcall: (funcall fn arg...) ----
		// (funcall) goes on to the generic application, whose wrapper reports the count.
		getLocal(w, OFF);
		i32(w, off.of(LispNames.FUNCALL));
		w.write(Instruction.I32_EQ);
		getLocal(w, REST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		emitEvalCar(w, REST, ENV);
		setLocal(w, FN);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		emitBuildArgList(w, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP, identityHash);
		getLocal(w, FN);
		getLocal(w, ARGHEAD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_APPLY);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// mapcar, mapc, reduce, first ... tenth, rest and nth have no arm: their
		// registered wrappers take every shape (mapcar over several lists, reduce with
		// :from-end), report a wrong count naming the operator and signal the
		// interpreter's type-error on a non-list, where the arms this replaced answered
		// one list only, took a :from-end for the initial value, dropped a surplus
		// argument and trapped on (first nil).

		// ---- list (variadic) ----
		openSpecial(w, OFF, off.of(LispNames.LIST));
		emitBuildArgList(w, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP, identityHash);
		getLocal(w, ARGHEAD);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- variadic + - * / : left-fold via the binary wrapper ----
		getLocal(w, OFF);
		i32(w, off.of(LispNames.ADD));
		w.write(Instruction.I32_EQ);
		getLocal(w, OFF);
		i32(w, off.of(LispNames.SUB));
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, OFF);
		i32(w, off.of(LispNames.MUL));
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, OFF);
		i32(w, off.of(LispNames.DIV));
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		// no argument: the wrapper answers the identity ((+) is 0) or reports the count
		// ((-) expects at least 1 argument)
		getLocal(w, REST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		getLocal(w, OFF);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		setLocal(w, ADDR);
		getLocal(w, ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		emitNull(w);
		WasmEmitHelper.emitNewClosure(w, identityHash);
		setLocal(w, FN);
		emitEvalCar(w, REST, ENV);
		setLocal(w, ACC);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		// single argument: (- x) negates and (/ x) takes the reciprocal, by seeding
		// the fold with the identity element (0 - x, 1 / x)
		getLocal(w, REST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, OFF);
		i32(w, off.of(LispNames.SUB));
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, FN);
		i32(w, 0);
		i31New(w);
		getLocal(w, ACC);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 2);
		setLocal(w, ACC);
		w.write(Instruction.END);
		getLocal(w, OFF);
		i32(w, off.of(LispNames.DIV));
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, FN);
		i32(w, 1);
		i31New(w);
		getLocal(w, ACC);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 2);
		setLocal(w, ACC);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, REST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, FN);
		getLocal(w, ACC);
		emitEvalCar(w, REST, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 2);
		setLocal(w, ACC);
		emitCdrOf(w, REST);
		setLocal(w, REST);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// ---- = < > <= >= /= : every argument evaluated, then each pair tested ----
		// The wrappers are binary (a sort predicate stays a two-argument call), so a
		// chain goes pairwise through them here: adjacent pairs for the ordering
		// operators, every pair for /=. The registry path below would evaluate every
		// argument too and then report the binary wrapper's count.
		emitComparisonChain(w, off, counts, REST, ENV, FN, ACC, ELEM, ARGHEAD, ARGTAIL, NEWCELL, TMP, OFF, ADDR, ARITY,
				IDX, CH, identityHash);

		// ---- generic named application ----
		// Lisp-2: the operator resolves in the function namespace only. Variable
		// bindings (lexical or global) never shadow a function.
		// (a) operator defined at runtime via defun (the $fenv function namespace)
		getLocal(w, OFF);
		emitGetGlobalFenv(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		setLocal(w, TMP);
		getLocal(w, TMP);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitCdrOf(w, TMP);
		setLocal(w, FN);
		emitBuildArgList(w, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP, identityHash);
		getLocal(w, FN);
		getLocal(w, ARGHEAD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_APPLY);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// (b) registered function -> evaluate every argument form, then apply. The count
		// is the spread dispatcher's to judge -- its case measures the list against the
		// callee's lambda list and reports a wrong count naming the operator -- so no
		// argument is dropped or padded here (evaluating exactly the registered arity
		// answered (car 1 2) with a type-error on 1 and (cons 1) with (1)).
		getLocal(w, OFF);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		setLocal(w, ADDR);
		getLocal(w, ADDR);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		emitNull(w);
		WasmEmitHelper.emitNewClosure(w, identityHash);
		setLocal(w, FN);
		emitBuildArgList(w, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP, identityHash);
		getLocal(w, FN);
		getLocal(w, ARGHEAD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_APPLY);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// (c) car/cdr composition such as cadr (operator matches c[ad]+r)
		emitCarCdrComposition(w, OP, LEN, REST, ENV, ACC, IDX, CH, ARITY);
		// (d) unknown operator
		emitNull(w);
		w.write(Instruction.RETURN);

		w.write(Instruction.ELSE);
		// operator is not a symbol (e.g. an inline lambda): evaluate it to a function
		getLocal(w, OP);
		getLocal(w, ENV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
		setLocal(w, FN);
		emitBuildArgList(w, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP, identityHash);
		getLocal(w, FN);
		getLocal(w, ARGHEAD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_APPLY);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // symbol-vs-non-symbol if/else

		emitNull(w); // unreachable, satisfies result type
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/** The comparison operators {@code _eval} chains pairwise over binary wrappers. */
	static final List<String> COMPARISON_OPERATORS = List.of(LispNames.EQ, LispNames.LT, LispNames.GT, LispNames.LE,
			LispNames.GE, LispNames.NE);

	/**
	 * The operators whose {@code _eval} arm checks its own argument count: the comparison
	 * chain's empty call, and {@code eval}, which no wrapper backs.
	 */
	static final List<String> SELF_COUNTED_OPERATORS = java.util.stream.Stream
		.concat(COMPARISON_OPERATORS.stream(), java.util.stream.Stream.of(LispNames.EVAL))
		.toList();

	/**
	 * What the arms that check their own argument count report a wrong one through: the
	 * {@code _arity_chk} index ({@code -1} where the module reports no wrong count, and
	 * the call traps instead) and each operator's shape
	 * ({@code WasmRuntimeBuilder.arityShape}, carrying the id its report is named by).
	 *
	 * @param arityChkIndex the {@code _arity_chk} function index, or -1
	 * @param shapes the shape per {@link #SELF_COUNTED_OPERATORS} name
	 */
	record CountChecks(int arityChkIndex, Map<String, Integer> shapes) {

		CountChecks {
			shapes = Map.copyOf(shapes);
		}

		int shape(String operator) {
			return java.util.Objects.requireNonNull(this.shapes.get(operator), operator);
		}

	}

	/**
	 * Emits the wrong-count report of {@code operator} over the argument-form list in
	 * {@code restSlot}: {@code _arity_chk} throws the interpreter's program-error, and
	 * the {@code unreachable} after it is what a module that reports no count gets.
	 */
	private static void emitCountReport(WasmWriter w, CountChecks counts, String operator, int restSlot) {
		if (counts.arityChkIndex() >= 0) {
			getLocal(w, restSlot);
			i32(w, counts.shape(operator));
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(counts.arityChkIndex());
			w.write(Instruction.DROP);
		}
		w.write(Instruction.UNREACHABLE);
	}

	/**
	 * Pushes 1 when the argument-form list in {@code restSlot} holds exactly
	 * {@code count} forms, else 0. Clobbers {@code cursorSlot}.
	 */
	private static void emitArgCountIs(WasmWriter w, int restSlot, int cursorSlot, int count) {
		w.write(Instruction.BLOCK, Type.I32);
		getLocal(w, restSlot);
		setLocal(w, cursorSlot);
		for (int i = 0; i < count; i++) {
			getLocal(w, cursorSlot);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.IF, 0x40);
			i32(w, 0);
			w.write(Instruction.BR, 1);
			w.write(Instruction.END);
			emitCdrOf(w, cursorSlot);
			setLocal(w, cursorSlot);
		}
		getLocal(w, cursorSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.END);
	}

	/**
	 * Emits the {@code _eval} arm for the comparison operators: {@code (< a b c)} is
	 * {@code (and (< a b) (< b c))} over arguments all evaluated first, as the
	 * interpreter does, and {@code (/= a b c)} tests every pair. With no argument it
	 * reports the interpreter's {@code < expects at least 1 argument, got 0} through
	 * {@code _arity_chk}; one argument answers {@code T}. Falls through for any other
	 * operator.
	 */
	private static void emitComparisonChain(WasmWriter w, SpecialFormOffsets off, CountChecks counts, int restSlot,
			int envSlot, int fnSlot, int leftSlot, int rightSlot, int headSlot, int tailSlot, int cellSlot, int tmpSlot,
			int offSlot, int addrSlot, int shapeSlot, int allPairsSlot, int matchedSlot, boolean identityHash) {
		i32(w, 0);
		setLocal(w, matchedSlot);
		for (String operator : COMPARISON_OPERATORS) {
			getLocal(w, offSlot);
			i32(w, off.of(operator));
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			i32(w, counts.shape(operator));
			setLocal(w, shapeSlot);
			i32(w, LispNames.NE.equals(operator) ? 1 : 0);
			setLocal(w, allPairsSlot);
			i32(w, 1);
			setLocal(w, matchedSlot);
			w.write(Instruction.END);
		}
		getLocal(w, matchedSlot);
		w.write(Instruction.IF, 0x40);
		// no argument at all: _arity_chk measures the empty list and throws
		getLocal(w, restSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		if (counts.arityChkIndex() >= 0) {
			getLocal(w, restSlot);
			getLocal(w, shapeSlot);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(counts.arityChkIndex());
			w.write(Instruction.DROP);
		}
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		// fn = the binary wrapper, as a function value
		getLocal(w, offSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		setLocal(w, addrSlot);
		getLocal(w, addrSlot);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		emitNull(w);
		WasmEmitHelper.emitNewClosure(w, identityHash);
		setLocal(w, fnSlot);
		emitBuildArgList(w, restSlot, envSlot, headSlot, tailSlot, cellSlot, tmpSlot, identityHash);
		getLocal(w, headSlot);
		setLocal(w, leftSlot);
		w.write(Instruction.LOOP, 0x40); // outer: one left operand per round
		emitCdrOf(w, leftSlot);
		setLocal(w, rightSlot);
		getLocal(w, rightSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		i32(w, off.of("T"));
		i32(w, 1);
		WasmEmitHelper.emitStrBuildCall(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.BLOCK, 0x40); // next left
		w.write(Instruction.LOOP, 0x40); // inner: the right operands this left meets
		getLocal(w, rightSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, fnSlot);
		emitCarOf(w, leftSlot);
		emitCarOf(w, rightSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 2);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		getLocal(w, allPairsSlot);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		emitCdrOf(w, rightSlot);
		setLocal(w, rightSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // inner loop
		w.write(Instruction.END); // next left
		emitCdrOf(w, leftSlot);
		setLocal(w, leftSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // outer loop
		w.write(Instruction.END); // matched
	}

	/**
	 * Emits {@code getLocal(off); i32(target); i32.eq; if (void)} opening a special-form
	 * branch.
	 */
	private static void openSpecial(WasmWriter w, int offSlot, int target) {
		getLocal(w, offSlot);
		i32(w, target);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
	}

	/**
	 * Appends the cons cell in {@code cellSlot} to the list whose head/tail are tracked
	 * in {@code headSlot}/{@code tailSlot}.
	 */
	private static void emitAppendCell(WasmWriter w, int cellSlot, int headSlot, int tailSlot) {
		getLocal(w, headSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, cellSlot);
		setLocal(w, headSlot);
		getLocal(w, cellSlot);
		setLocal(w, tailSlot);
		w.write(Instruction.ELSE);
		getLocal(w, tailSlot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, cellSlot);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		getLocal(w, cellSlot);
		setLocal(w, tailSlot);
		w.write(Instruction.END);
	}

	// Pushes the unsigned content byte arr[idx] of the string value in strValSlot, where
	// pushIdx emits the 0-based array index. Reads go through the GC $str_bytes array
	// (old
	// linear[field0 + idx] == arr[idx]); recomputes the array per call, fine for the rare
	// eval-time symbol-name parsing path.
	private static void strByteAt(WasmWriter w, int strValSlot, Runnable pushIdx) {
		getLocal(w, strValSlot);
		WasmEmitHelper.emitStrBytesArray(w);
		pushIdx.run();
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
	}

	/**
	 * Emits handling for {@code car}/{@code cdr} composition operators ({@code c[ad]+r},
	 * e.g. {@code cadr}). When the operator name in {@code strValSlot} (length
	 * {@code lenSlot}) matches the pattern, evaluates the single argument and applies the
	 * car/cdr operations from right to left, then returns. Otherwise falls through.
	 * Clobbers the supplied scratch locals.
	 */
	private static void emitCarCdrComposition(WasmWriter w, int strValSlot, int lenSlot, int restSlot, int envSlot,
			int accSlot, int idxSlot, int chSlot, int validSlot) {
		// first/last byte check: len>=3 && arr[0]=='C' && arr[len-1]=='R'
		getLocal(w, lenSlot);
		i32(w, 3);
		w.write(Instruction.I32_GE_S);
		strByteAt(w, strValSlot, () -> i32(w, 0));
		i32(w, 0x43);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		strByteAt(w, strValSlot, () -> {
			getLocal(w, lenSlot);
			i32(w, 1);
			w.write(Instruction.I32_SUB);
		});
		i32(w, 0x52);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		// scan middle bytes: valid = all in {'A','D'}
		i32(w, 1);
		setLocal(w, validSlot);
		i32(w, 1);
		setLocal(w, idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		getLocal(w, lenSlot);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		strByteAt(w, strValSlot, () -> getLocal(w, idxSlot));
		setLocal(w, chSlot);
		getLocal(w, chSlot);
		i32(w, 0x41);
		w.write(Instruction.I32_NE);
		getLocal(w, chSlot);
		i32(w, 0x44);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		setLocal(w, validSlot);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		getLocal(w, idxSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// if valid: eval arg and apply car/cdr from right to left
		getLocal(w, validSlot);
		w.write(Instruction.IF, 0x40);
		emitEvalCar(w, restSlot, envSlot);
		setLocal(w, accSlot);
		getLocal(w, lenSlot);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		i32(w, 1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 1);
		strByteAt(w, strValSlot, () -> getLocal(w, idxSlot));
		i32(w, 0x41);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		emitCarOf(w, accSlot);
		setLocal(w, accSlot);
		w.write(Instruction.ELSE);
		emitCdrOf(w, accSlot);
		setLocal(w, accSlot);
		w.write(Instruction.END);
		getLocal(w, idxSlot);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, accSlot);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * Emits a {@code progn} loop: evaluates each form in the list at {@code restSlot} in
	 * the environment at {@code envSlot}, leaving the last value in {@code accSlot} (nil
	 * for an empty list). Consumes {@code restSlot}.
	 */
	private static void emitPrognLoop(WasmWriter w, int restSlot, int envSlot, int accSlot) {
		emitNull(w);
		setLocal(w, accSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, restSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		emitEvalCar(w, restSlot, envSlot);
		setLocal(w, accSlot);
		emitCdrOf(w, restSlot);
		setLocal(w, restSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/**
	 * Emits a loop that evaluates each form in the list at {@code restSlot} (in the
	 * environment at {@code envSlot}) and links the results into a fresh proper list
	 * whose head is left in {@code headSlot}. Consumes {@code restSlot} and clobbers
	 * {@code tailSlot}, {@code cellSlot} and {@code tmpSlot}.
	 */
	private static void emitBuildArgList(WasmWriter w, int restSlot, int envSlot, int headSlot, int tailSlot,
			int cellSlot, int tmpSlot, boolean identityHash) {
		emitNull(w);
		setLocal(w, headSlot);
		emitNull(w);
		setLocal(w, tailSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, restSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		// v = eval(car rest); cell = cons(v, null)
		emitEvalCar(w, restSlot, envSlot);
		setLocal(w, tmpSlot);
		getLocal(w, tmpSlot);
		emitNull(w);
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, cellSlot);
		// append cell
		getLocal(w, headSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, cellSlot);
		setLocal(w, headSlot);
		getLocal(w, cellSlot);
		setLocal(w, tailSlot);
		w.write(Instruction.ELSE);
		getLocal(w, tailSlot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, cellSlot);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		getLocal(w, cellSlot);
		setLocal(w, tailSlot);
		w.write(Instruction.END); // if
		emitCdrOf(w, restSlot);
		setLocal(w, restSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	// === _apply: (fn, argList) -> value ===

	/**
	 * Builds {@code _apply}:
	 * {@code ((ref null eq) fn, (ref null eq) argList) -> (ref null
	 * eq)}. {@code argList} is a proper list of already-evaluated argument values.
	 * Interpreted closures (sentinel {@code funcId == -1}) bind their parameters to the
	 * arguments over their captured environment and evaluate their body; compiled
	 * closures are dispatched through the arity dispatch function matching the argument
	 * count.
	 * @param usesEval whether the {@code _eval} interpreter exists (and with it the
	 * {@code $fenv} and interpreted-closure arms)
	 * @param arityChkIndex the {@code _arity_chk} function index an interpreted closure's
	 * wrong count is reported through, or -1 where the module reports none
	 * @param identityHash whether conses and closures carry an identity hash
	 * @return the encoded function body
	 */
	static byte[] buildApplyBody(boolean usesEval, int arityChkIndex, boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// params: 0 = FN, 1 = ARGLIST
		// ref locals: 2..14 (PARAMS, NEWENV, BODY, PAIR, TMP, ARGCUR, ARG0..ARG6)
		// i32 locals: 15..16 (FUNCID, LEN)
		w.write(2);
		w.write(13);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I32);

		final int FN = 0, ARGLIST = 1, PARAMS = 2, NEWENV = 3, BODY = 4, PAIR = 5, TMP = 6, ARGCUR = 7, ARG0 = 8;
		final int FUNCID = 15, LEN = 16;

		// fn is null: NIL names no function. The spread dispatcher's prologue is where
		// a value that names none is reported (a throw in EH mode, a trap outside it),
		// so every such arm below hands the ORIGINAL value over to it.
		getLocal(w, FN);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitSpreadDispatch(w, FN, ARGLIST);
		w.write(Instruction.END);

		// symbol designator (CL-style): a symbol resolves in the function namespace
		// ($fenv then the compiled registry; without the _eval interpreter no $fenv
		// binding can exist, so the registry is probed directly) and the result
		// replaces fn
		getLocal(w, FN);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, FN);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, FUNCID); // scratch: name offset
		if (usesEval) {
			// $fenv binding?
			getLocal(w, FUNCID);
			emitGetGlobalFenv(w);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
			setLocal(w, TMP);
			getLocal(w, TMP);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.IF, 0x40);
			emitCdrOf(w, TMP);
			setLocal(w, FN);
			w.write(Instruction.ELSE);
		}
		// compiled registry?
		getLocal(w, FUNCID);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		setLocal(w, LEN); // scratch: record address
		getLocal(w, LEN);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, LEN);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		emitNull(w);
		WasmEmitHelper.emitNewClosure(w, identityHash);
		setLocal(w, FN);
		w.write(Instruction.ELSE);
		// A symbol that resolves in neither $fenv nor the registry is an undefined
		// function: fail LOUDLY through the funcall dispatchers' own miss arm
		// (returning nil here silently swallowed (apply (intern "NOSUCH") ...)).
		emitSpreadDispatch(w, FN, ARGLIST);
		w.write(Instruction.END);
		if (usesEval) {
			w.write(Instruction.END);
		}
		w.write(Instruction.END);

		// if fn is a closure struct
		getLocal(w, FN);
		refTest(w, WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);

		getLocal(w, FN);
		refCast(w, WasmLispCompiler.TYPE_CLOSURE);
		structGet(w, WasmLispCompiler.TYPE_CLOSURE, 0);
		setLocal(w, FUNCID);

		// interpreted closure? (only _eval creates the funcId == -1 sentinel, so the
		// arm exists only with the interpreter)
		if (usesEval) {
			getLocal(w, FUNCID);
			i32(w, -1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			// envField = fn.env = cons(lambdaTail, capturedEnv)
			getLocal(w, FN);
			refCast(w, WasmLispCompiler.TYPE_CLOSURE);
			structGet(w, WasmLispCompiler.TYPE_CLOSURE, 1);
			setLocal(w, TMP);
			emitCarOf(w, TMP);
			setLocal(w, PAIR); // lambdaTail = ((params) body...)
			emitCdrOf(w, TMP);
			setLocal(w, NEWENV); // capturedEnv
			emitCarOf(w, PAIR);
			setLocal(w, PARAMS); // (params)
			emitCdrOf(w, PAIR);
			setLocal(w, BODY); // (body...)
			if (arityChkIndex >= 0) {
				emitClosureArityCheck(w, PARAMS, ARGLIST, ARGCUR, ARG0, LEN, arityChkIndex);
			}
			// bind params to args
			getLocal(w, ARGLIST);
			setLocal(w, ARGCUR);
			w.write(Instruction.BLOCK, 0x40);
			w.write(Instruction.LOOP, 0x40);
			getLocal(w, PARAMS);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.BR_IF, 1);
			// pval = argcur null ? null : car(argcur)
			emitArgcurHeadOrNull(w, ARGCUR, true);
			setLocal(w, TMP);
			// pair = cons(car(params), pval)
			emitCarOf(w, PARAMS);
			getLocal(w, TMP);
			WasmEmitHelper.emitNewCons(w, identityHash);
			setLocal(w, PAIR);
			// newEnv = cons(pair, newEnv)
			getLocal(w, PAIR);
			getLocal(w, NEWENV);
			WasmEmitHelper.emitNewCons(w, identityHash);
			setLocal(w, NEWENV);
			// params = cdr(params); argcur = argcur null ? null : cdr(argcur)
			emitCdrOf(w, PARAMS);
			setLocal(w, PARAMS);
			emitArgcurHeadOrNull(w, ARGCUR, false);
			setLocal(w, ARGCUR);
			w.write(Instruction.BR, 0);
			w.write(Instruction.END); // loop
			w.write(Instruction.END); // block
			// evaluate body as progn in newEnv
			emitPrognLoop(w, BODY, NEWENV, TMP);
			getLocal(w, TMP);
			w.write(Instruction.RETURN);
			w.write(Instruction.END); // if interpreted
		}

		// compiled closure: dispatch by argument count
		i32(w, 0);
		setLocal(w, LEN);
		getLocal(w, ARGLIST);
		setLocal(w, ARGCUR);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, ARGCUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, LEN);
		emitCdrOf(w, ARGCUR);
		setLocal(w, ARGCUR);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		// One call, any argument count: the SPREAD dispatcher takes the list whole and
		// each case reads its target's required parameters out of it, handing a variadic
		// target the remaining tail. The per-arity dispatchers cannot serve apply -- they
		// take one WASM parameter per Lisp argument, so they stop at MAX_CALLABLE_ARITY,
		// and an apply past it used to fall off the ladder and trap. A tail call, like
		// every dispatch: an (apply f ...) in tail position whose target applies again
		// otherwise leaves one _apply frame per round.
		emitSpreadDispatch(w, FN, ARGLIST);
		w.write(Instruction.END); // if closure

		// not callable: the dispatcher reports it
		emitSpreadDispatch(w, FN, ARGLIST);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Emits the wrong-count check of an interpreted closure: {@code _arity_chk(argList,
	 * 2 * params)}, reported as {@code Function expects N argument(s), got M} like any
	 * anonymous callee. A lambda list with a {@code &}-marker is left unchecked: the
	 * runtime {@code lambda} binds such a list positionally (a documented limitation), so
	 * its parameter count is no count the call has to match. Clobbers the cursor, element
	 * and count locals.
	 */
	private static void emitClosureArityCheck(WasmWriter w, int paramsSlot, int argListSlot, int cursorSlot,
			int elemSlot, int countSlot, int arityChkIndex) {
		i32(w, 0);
		setLocal(w, countSlot);
		getLocal(w, paramsSlot);
		setLocal(w, cursorSlot);
		w.write(Instruction.BLOCK, 0x40); // unchecked
		w.write(Instruction.BLOCK, 0x40); // counted
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, cursorSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		emitCarOf(w, cursorSlot);
		setLocal(w, elemSlot);
		getLocal(w, elemSlot);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, elemSlot);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
		w.write(Instruction.IF, 0x40);
		strByteAt(w, elemSlot, () -> i32(w, 0));
		i32(w, '&');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 4); // if, if, loop, counted -> unchecked
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, countSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, countSlot);
		emitCdrOf(w, cursorSlot);
		setLocal(w, cursorSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // counted
		getLocal(w, argListSlot);
		getLocal(w, countSlot);
		i32(w, 1);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(arityChkIndex);
		w.write(Instruction.DROP);
		w.write(Instruction.END); // unchecked
	}

	/**
	 * Emits {@code return _dispatch_spread(fn, argList)} as a tail call: an {@code apply}
	 * in tail position then runs in constant stack all the way to the target.
	 */
	private static void emitSpreadDispatch(WasmWriter w, int fnSlot, int argListSlot) {
		getLocal(w, fnSlot);
		getLocal(w, argListSlot);
		w.write(Instruction.RETURN_CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_SPREAD);
	}

	/**
	 * Emits {@code argcur == null ? null : car(argcur)} (when {@code head} is true) or
	 * {@code argcur == null ? null : cdr(argcur)} (when false), leaving one
	 * {@code (ref null eq)} on the stack.
	 */
	private static void emitArgcurHeadOrNull(WasmWriter w, int argcurSlot, boolean head) {
		getLocal(w, argcurSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		emitNull(w);
		w.write(Instruction.ELSE);
		if (head) {
			emitCarOf(w, argcurSlot);
		}
		else {
			emitCdrOf(w, argcurSlot);
		}
		w.write(Instruction.END);
	}

	// === _store: (place, value, env) -> value ===

	/**
	 * Builds {@code _store}:
	 * {@code ((ref null eq) place, (ref null eq) value, (ref null eq)
	 * env) -> (ref null eq)}, the assignment primitive shared by {@code setq},
	 * {@code setf}, {@code push} and {@code pop}.
	 *
	 * <p>
	 * For a symbol place it mutates the lexical or global binding (creating a global
	 * binding if none exists). For an accessor place it mutates the appropriate cons
	 * cell: {@code (car x)}/{@code (first x)} and the {@code c[ad]+r} compositions set
	 * the car/cdr of the resolved target; {@code (cdr x)} sets the cdr; {@code (nth n x)}
	 * and {@code (second/third/fourth x)} walk the list and set the car. Unsupported
	 * places are a no-op. Always returns {@code value}.
	 * @param off the string-table offsets of the accessor symbols
	 * @return the encoded function body
	 */
	static byte[] buildStoreBody(SpecialFormOffsets off, boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// params: 0 = PLACE, 1 = VALUE, 2 = ENV
		// ref locals: 3..5, i32 locals: 6..10
		w.write(2);
		w.write(3);
		w.writeRefType(true, Type.EQ.code());
		w.write(5);
		w.write(Type.I32);

		final int PLACE = 0, VALUE = 1, ENV = 2, OP = 3, TMP = 4, TARGET = 5;
		final int OFF = 6, LEN = 7, IDX = 8, FIELD = 9, CH = 10;

		// --- symbol place: variable assignment ---
		getLocal(w, PLACE);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		getLocal(w, PLACE);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, OFF);
		// lexical binding?
		emitStoreToBindingIfFound(w, OFF, ENV, VALUE, TMP, false);
		// global binding?
		emitStoreToBindingIfFound(w, OFF, ENV, VALUE, TMP, true);
		// not bound anywhere: prepend a new binding to the global environment
		getLocal(w, PLACE);
		getLocal(w, VALUE);
		WasmEmitHelper.emitNewCons(w, identityHash);
		emitGetGlobalEnv(w);
		WasmEmitHelper.emitNewCons(w, identityHash);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
		getLocal(w, VALUE);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // end symbol-if

		// --- accessor place: (op arg...) ---
		getLocal(w, PLACE);
		refTest(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, VALUE);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitCarOf(w, PLACE);
		setLocal(w, OP);
		getLocal(w, OP);
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, VALUE);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		getLocal(w, OP);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, OFF);
		getLocal(w, OP);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
		setLocal(w, LEN);
		emitCdrOf(w, PLACE);
		setLocal(w, TMP); // args
		// FIELD = -1 means "no target resolved"
		i32(w, -1);
		setLocal(w, FIELD);

		// nth: walk n cdrs, set car
		openSpecial(w, OFF, off.of(LispNames.NTH));
		emitEvalCar(w, TMP, ENV);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, IDX);
		emitCdrOf(w, TMP);
		setLocal(w, TMP);
		emitEvalCar(w, TMP, ENV);
		setLocal(w, TARGET);
		emitWalkCdrs(w, TARGET, IDX);
		i32(w, 0);
		setLocal(w, FIELD);
		w.write(Instruction.END);

		// first/second/third/fourth/fifth/sixth/seventh/eighth/ninth/tenth: k cdrs, set
		// car
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.FIRST), TMP, ENV, TARGET, FIELD, 0);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.SECOND), TMP, ENV, TARGET, FIELD, 1);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.THIRD), TMP, ENV, TARGET, FIELD, 2);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.FOURTH), TMP, ENV, TARGET, FIELD, 3);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.FIFTH), TMP, ENV, TARGET, FIELD, 4);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.SIXTH), TMP, ENV, TARGET, FIELD, 5);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.SEVENTH), TMP, ENV, TARGET, FIELD, 6);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.EIGHTH), TMP, ENV, TARGET, FIELD, 7);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.NINTH), TMP, ENV, TARGET, FIELD, 8);
		emitFixedAccessorTarget(w, OFF, off.of(LispNames.TENTH), TMP, ENV, TARGET, FIELD, 9);

		// car/cdr and c[ad]+r compositions (only if no named accessor matched)
		getLocal(w, FIELD);
		i32(w, -1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		emitCarCdrStoreTarget(w, OP, LEN, TMP, ENV, TARGET, FIELD, IDX, CH);
		w.write(Instruction.END);

		// store: if a target was resolved and is a cons, set car (FIELD 0) or cdr (FIELD
		// 1)
		getLocal(w, FIELD);
		i32(w, -1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		getLocal(w, TARGET);
		refTest(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.IF, 0x40);
		getLocal(w, FIELD);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, TARGET);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, VALUE);
		structSet(w, WasmLispCompiler.TYPE_CONS, 0);
		w.write(Instruction.ELSE);
		getLocal(w, TARGET);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, VALUE);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);

		getLocal(w, VALUE);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Emits {@code binding = _env_lookup(off, env-or-global); if (binding != null) {
	 * binding.cdr = value; return value; }}. Used by {@code _store} for the lexical
	 * ({@code global == false}) and global ({@code global == true}) environments.
	 */
	private static void emitStoreToBindingIfFound(WasmWriter w, int offSlot, int envSlot, int valueSlot, int tmpSlot,
			boolean global) {
		getLocal(w, offSlot);
		if (global) {
			emitGetGlobalEnv(w);
		}
		else {
			getLocal(w, envSlot);
		}
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		setLocal(w, tmpSlot);
		getLocal(w, tmpSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, tmpSlot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, valueSlot);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		getLocal(w, valueSlot);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Emits the {@code _store} handling of a fixed numbered accessor (e.g.
	 * {@code second}): if the operator at {@code offSlot} equals {@code target},
	 * evaluates the single argument, walks {@code cdrCount} cdrs into {@code targetSlot}
	 * and sets {@code fieldSlot} to 0 (car).
	 */
	private static void emitFixedAccessorTarget(WasmWriter w, int offSlot, int target, int argsSlot, int envSlot,
			int targetSlot, int fieldSlot, int cdrCount) {
		openSpecial(w, offSlot, target);
		emitEvalCar(w, argsSlot, envSlot);
		setLocal(w, targetSlot);
		for (int i = 0; i < cdrCount; i++) {
			emitCdrOf(w, targetSlot);
			setLocal(w, targetSlot);
		}
		i32(w, 0);
		setLocal(w, fieldSlot);
		w.write(Instruction.END);
	}

	/**
	 * Emits a loop that replaces {@code targetSlot} with its cdr {@code idxSlot} times
	 * (stopping at nil).
	 */
	private static void emitWalkCdrs(WasmWriter w, int targetSlot, int idxSlot) {
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		i32(w, 0);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, targetSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		emitCdrOf(w, targetSlot);
		setLocal(w, targetSlot);
		getLocal(w, idxSlot);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * Emits the {@code _store} handling of {@code car}/{@code cdr} and the
	 * {@code c[ad]+r} compositions (e.g. {@code cadr}). When the operator name in
	 * {@code strValSlot} (length {@code
	 * lenSlot}) matches the pattern, evaluates the single argument, applies the inner
	 * operations into {@code targetSlot}, and sets {@code fieldSlot} to 0 (the outer op
	 * is {@code a}) or 1 (it is {@code d}). Otherwise leaves {@code fieldSlot} unchanged.
	 */
	private static void emitCarCdrStoreTarget(WasmWriter w, int strValSlot, int lenSlot, int argsSlot, int envSlot,
			int targetSlot, int fieldSlot, int idxSlot, int validSlot) {
		// first/last byte check: len>=3 && arr[0]=='C' && arr[len-1]=='R'
		getLocal(w, lenSlot);
		i32(w, 3);
		w.write(Instruction.I32_GE_S);
		strByteAt(w, strValSlot, () -> i32(w, 0));
		i32(w, 0x43);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		strByteAt(w, strValSlot, () -> {
			getLocal(w, lenSlot);
			i32(w, 1);
			w.write(Instruction.I32_SUB);
		});
		i32(w, 0x52);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		// scan middle bytes: valid = all in {'A','D'}
		i32(w, 1);
		setLocal(w, validSlot);
		i32(w, 1);
		setLocal(w, idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		getLocal(w, lenSlot);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		strByteAt(w, strValSlot, () -> getLocal(w, idxSlot));
		i32(w, 0x41);
		w.write(Instruction.I32_NE);
		strByteAt(w, strValSlot, () -> getLocal(w, idxSlot));
		i32(w, 0x44);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		setLocal(w, validSlot);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		getLocal(w, idxSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// if valid: build target and field
		getLocal(w, validSlot);
		w.write(Instruction.IF, 0x40);
		emitEvalCar(w, argsSlot, envSlot);
		setLocal(w, targetSlot);
		// apply inner ops (indices len-2 down to 2)
		getLocal(w, lenSlot);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		i32(w, 2);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 1);
		strByteAt(w, strValSlot, () -> getLocal(w, idxSlot));
		i32(w, 0x41);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		emitCarOf(w, targetSlot);
		setLocal(w, targetSlot);
		w.write(Instruction.ELSE);
		emitCdrOf(w, targetSlot);
		setLocal(w, targetSlot);
		w.write(Instruction.END);
		getLocal(w, idxSlot);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// field = (arr[1] == 'D') ? 1 : 0
		i32(w, 0);
		setLocal(w, fieldSlot);
		strByteAt(w, strValSlot, () -> i32(w, 1));
		i32(w, 0x44);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		setLocal(w, fieldSlot);
		w.write(Instruction.END);
		w.write(Instruction.END); // end valid-if
		w.write(Instruction.END); // end first/last-if
	}

}
