package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds WASM bytecode for runtime helper functions: dispatch, print_i32, write_str,
 * print_val, and print_f64.
 */
final class WasmRuntimeBuilder {

	private WasmRuntimeBuilder() {
	}

	/**
	 * Builds the _append helper function body. Takes two (ref null eq) args, returns (ref
	 * null eq). If a is null, returns b. Otherwise, creates struct.new cons(a.car,
	 * _append(a.cdr, b)).
	 */
	static byte[] buildAppendBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // 0 extra locals

		// if a is null, return b
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // a
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // b
		w.write(Instruction.ELSE);

		// a.car
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0); // field 0: car

		// _append(a.cdr, b)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1); // field 1: cdr
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // b
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_APPEND);

		// struct.new cons(car, result)
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);

		w.write(Instruction.END); // end if

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the _equal helper function body (structural equality). Takes two (ref null
	 * eq) args (locals 0 and 1), returns i32 (1=equal, 0=not). Identical references
	 * (ref.eq, which also covers i31 integers) are equal; two cons cells are equal when
	 * their cars and cdrs are recursively _equal; otherwise it reproduces eql semantics
	 * for the remaining value types (floats by value, ratios by numerator/denominator,
	 * symbols and strings by interned offset).
	 */
	static byte[] buildEqualBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // 0 extra locals; params (local 0 = a, local 1 = b) suffice

		// if (ref.eq a b) -> 1
		getLocal(w, 0);
		getLocal(w, 1);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.ELSE);

		// else if both cons -> _equal(car, car) && _equal(cdr, cdr)
		refTest(w, 0, WasmLispCompiler.TYPE_CONS);
		refTest(w, 1, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		consField(w, 0, 0); // a.car
		consField(w, 1, 0); // b.car
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		consField(w, 0, 1); // a.cdr
		consField(w, 1, 1); // b.cdr
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end car-equal if
		w.write(Instruction.ELSE);

		// else (ref.eq already false): eql base case for value types.
		// both floats -> f64 fields equal
		refTest(w, 0, WasmLispCompiler.TYPE_FLOAT);
		refTest(w, 1, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		floatField(w, 0);
		floatField(w, 1);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.ELSE);

		// both ratios -> numerators and denominators equal
		refTest(w, 0, WasmLispCompiler.TYPE_RATIO);
		refTest(w, 1, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		ratioComponent(w, 0, WasmLispCompiler.FUNC_RAT_NUM);
		ratioComponent(w, 1, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.I32_EQ);
		ratioComponent(w, 0, WasmLispCompiler.FUNC_RAT_DEN);
		ratioComponent(w, 1, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.ELSE);

		// symbols and strings -> interned offsets equal
		emitStringOffsetEq(w);
		w.write(Instruction.END); // end ratio if
		w.write(Instruction.END); // end float if
		w.write(Instruction.END); // end both-cons if
		w.write(Instruction.END); // end ref.eq if

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	// 1 if both locals are TYPE_STRING structs with the same data offset, else 0.
	private static void emitStringOffsetEq(WasmWriter w) {
		refTest(w, 0, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF);
		w.write(Type.I32);
		refTest(w, 1, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF);
		w.write(Type.I32);
		stringOffset(w, 0);
		stringOffset(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end b-string if
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end a-string if
	}

	private static void getLocal(WasmWriter w, int idx) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(idx);
	}

	private static void refTest(WasmWriter w, int local, int typeIndex) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(typeIndex);
	}

	private static void consField(WasmWriter w, int local, int field) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(field);
	}

	private static void floatField(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
	}

	private static void stringOffset(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
	}

	private static void ratioComponent(WasmWriter w, int local, int func) {
		getLocal(w, local);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(func);
	}

	static byte[] buildDispatchBody(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, int numDefuns, WasmLispCompiler.StringTable st,
			boolean usesEval) {
		// Collect all functions with matching arity
		record Target(int funcId, int funcIndex) {
		}
		List<Target> targets = new ArrayList<>();
		for (int i = 0; i < defuns.size(); i++) {
			if (defuns.get(i).paramNames().size() == arity) {
				targets.add(new Target(i, WasmLispCompiler.FUNC_USER_BASE + i));
			}
		}
		for (int i = 0; i < lambdaDecls.size(); i++) {
			WasmLispCompiler.LambdaInfo lambda = lambdaDecls.get(i);
			if (lambda.paramNames().size() == arity) {
				targets.add(new Target(lambda.funcId(), lambda.funcIndex()));
			}
		}

		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: param 0 = funcval, params 1..arity = args
		// Extra locals: funcId (i32) and the arg list for the _apply fallback (ref)
		w.write(2); // 2 local groups
		w.write(1); // 1 local of type i32
		w.write(Type.I32);
		w.write(1); // 1 local of type (ref null eq)
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		int funcIdLocal = arity + 1; // after params
		int argListLocal = arity + 2;

		// Extract funcId from closure struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // funcval
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeSignedLeb128(0); // field 0: funcId
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		// Interpreted closure (funcId == -1, created by the eval runtime's lambda):
		// delegate to _apply with the arguments collected into a cons list
		if (usesEval) {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(funcIdLocal);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(-1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			w.write(Instruction.SET_LOCAL);
			w.writeSignedLeb128(argListLocal);
			for (int a = arity; a >= 1; a--) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(a);
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(argListLocal);
				w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
				w.write(Instruction.SET_LOCAL);
				w.writeSignedLeb128(argListLocal);
			}
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(0);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(argListLocal);
			w.write(Instruction.CALL);
			w.writeSignedLeb128(WasmLispCompiler.FUNC_APPLY);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}

		if (targets.isEmpty()) {
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}

		int numCases = targets.size();
		int maxFuncId = 0;
		for (Target t : targets) {
			maxFuncId = Math.max(maxFuncId, t.funcId);
		}

		// Result block (typed)
		w.write(Instruction.BLOCK);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		// Default block (void)
		w.write(Instruction.BLOCK, 0x40);

		// Case blocks (void) - outermost case first
		for (int i = 0; i < numCases; i++) {
			w.write(Instruction.BLOCK, 0x40);
		}

		// Load funcId and br_table
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		// Build funcId -> br_table depth mapping
		Map<Integer, Integer> funcIdToCase = new HashMap<>();
		for (int j = 0; j < targets.size(); j++) {
			funcIdToCase.put(targets.get(j).funcId, j);
		}

		w.write(Instruction.BR_TABLE);
		w.writeUnsignedLeb128(maxFuncId + 1); // label count
		for (int fid = 0; fid <= maxFuncId; fid++) {
			Integer caseJ = funcIdToCase.get(fid);
			if (caseJ != null) {
				w.writeUnsignedLeb128(numCases - 1 - caseJ);
			}
			else {
				w.writeUnsignedLeb128(numCases); // default
			}
		}
		w.writeUnsignedLeb128(numCases); // default label

		// Case bodies: close blocks from innermost to outermost
		for (int k = 0; k < numCases; k++) {
			w.write(Instruction.END); // end of $case_{numCases-1-k}
			int targetIdx = numCases - 1 - k;
			Target target = targets.get(targetIdx);
			// Extract env from closure struct
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(0); // funcval
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
			w.writeSignedLeb128(1); // field 1: env
			// Push args
			for (int a = 1; a <= arity; a++) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(a);
			}
			// Call target function
			w.write(Instruction.CALL);
			w.writeSignedLeb128(target.funcIndex);
			// Break to $result block
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(numCases - k);
		}

		// End default block
		w.write(Instruction.END); // $default
		w.write(Instruction.UNREACHABLE);

		// End result block
		w.write(Instruction.END); // $result

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the _read_line helper function body. Reads one line from the given file
	 * descriptor (0 = stdin) using fd_read, byte by byte. Returns a string struct with
	 * '"' prefix/suffix (internal string format), or ref.null eq on EOF.
	 */
	static byte[] buildReadLineBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Param: 0=fd (i32)
		// Locals: 1=heap_ptr (i32), 2=pos (i32), 3=nread (i32), 4=eof_flag (i32)
		w.write(4);
		w.write(1);
		w.write(Type.I32); // local 1: heap_ptr
		w.write(1);
		w.write(Type.I32); // local 2: pos
		w.write(1);
		w.write(Type.I32); // local 3: nread
		w.write(1);
		w.write(Type.I32); // local 4: eof_flag

		// heap_ptr = memory[HEAP_PTR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		// memory[heap_ptr] = 0x22 ('"' prefix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// pos = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		// eof_flag = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4);

		// Loop: read one byte at a time
		w.write(Instruction.BLOCK, 0x40); // block $break
		w.write(Instruction.LOOP, 0x40); // loop $continue

		// Set iov: ptr = heap_ptr + pos, len = 1
		// iov_buf_ptr at IOV_OFFSET
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // heap_ptr
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // pos
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// iov_buf_len at IOV_OFFSET + 4
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET + 4);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// nread = fd_read(fd, iov, 1, nwritten_addr)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // fd param
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // iovs_len = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP); // drop errno

		// nread = memory[NWRITTEN_OFFSET]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// if nread == 0: set eof_flag, break
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.BR, 2); // break out of loop and block
		w.write(Instruction.END);

		// byte = memory[heap_ptr + pos]
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		// if byte == 0x0A (newline): break
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0A);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 1); // break out of block

		// pos++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0); // continue loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// if pos == 1 && eof_flag: return ref.null eq (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);

		// memory[heap_ptr + pos] = 0x22 ('"' suffix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// new_heap_ptr = heap_ptr + pos + 1
		// memory[HEAP_PTR_ADDR] = new_heap_ptr
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// return struct.new string(heap_ptr, pos + 1)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // offset
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // pos
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD); // length = pos + 1
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);

		w.write(Instruction.END); // end if/else

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the print_i32 helper function body.
	 */
	static byte[] buildPrintI32Core(boolean appendNewline) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(3);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.ELSE);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(45);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(10);
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
		}

		// Emit the rendered digits through _write_str so the capture mode of the
		// string runtime also sees integer output.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	static byte[] buildWriteStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 2=capture cursor (i32), 3=copy index (i32); params: 0=ptr, 1=len
		w.write(1);
		w.write(2);
		w.write(Type.I32);

		// Capture mode (string runtime): append the bytes at the capture cursor
		// instead of writing to stdout.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.IF, 0x40);

		// cur = memory[CAPTURE_CUR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		// i = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// while (i < len) { memory[cur + i] = memory[ptr + i]; i++; }
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// memory[CAPTURE_CUR_ADDR] = cur + len
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the body of a string-runtime function (_princ_to_str, _prin1_to_str or
	 * _string_concat). It renders the argument value(s) between two quote bytes into the
	 * heap by turning on the capture mode of {@code _write_str}, bumps the heap pointer
	 * and returns a new string struct over the captured bytes.
	 * @param renderFunc the rendering function ({@code FUNC_PRINC_VAL} for display text,
	 * {@code FUNC_PRINT_VAL} for the readable form)
	 * @param argCount 1 (to-string) or 2 (concatenation: both rendered back to back)
	 * @return the function body
	 */
	static byte[] buildToStringBody(int renderFunc, int argCount) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: argCount=start (i32), argCount+1=cur (i32)
		int start = argCount;
		int cur = argCount + 1;
		w.write(1);
		w.write(2);
		w.write(Type.I32);

		// start = memory[HEAP_PTR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(start);

		// memory[start] = 0x22 ('"' prefix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// memory[CAPTURE_CUR_ADDR] = start + 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// memory[CAPTURE_FLAG_ADDR] = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// Render each argument; _write_str appends the bytes at the capture cursor.
		for (int i = 0; i < argCount; i++) {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(i);
			w.write(Instruction.CALL);
			w.writeSignedLeb128(renderFunc);
		}

		// memory[CAPTURE_FLAG_ADDR] = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// cur = memory[CAPTURE_CUR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(cur);

		// memory[cur] = 0x22 ('"' suffix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(cur);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// memory[HEAP_PTR_ADDR] = cur + 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(cur);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// return struct.new string(start, cur + 1 - start)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(cur);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _print_val helper function that prints any Lisp value without a trailing
	 * newline. Handles null (nil), i31ref (integer), string struct, closure struct, and
	 * cons struct (list).
	 */
	static byte[] buildPrintValBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(2);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(1);
		w.write(Type.I32);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check ratio struct -> "numerator/denominator"
		emitPrintRatio(w, st);

		// Check float struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check string struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the princ_val helper function body. Same as print_val but strips quotes from
	 * strings and uses FUNC_PRINC_VAL for recursive cons printing.
	 */
	static byte[] buildPrincValBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Local declarations: slot 1 = ref null eq, slot 2 = i32 (offset), slot 3 = i32
		// (length)
		w.write(3);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check ratio struct -> "numerator/denominator"
		emitPrintRatio(w, st);

		// Check float struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check string struct - strip quotes if leading '"'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		// Get offset -> local 2
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);
		// Get length -> local 3
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);
		// Check if first byte is '"' (0x22)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// Strip quotes: offset+1, length-2
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.ELSE);
		// No quote: use offset and length as-is
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Emits the ratio branch shared by _print_val and _princ_val: if the value in
	// param 0 is a ratio struct, prints "numerator/denominator" and returns.
	private static void emitPrintRatio(WasmWriter w, WasmLispCompiler.StringTable st) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.slash.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.slash.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Builds the print_f64 helper function body. Prints integer part via print_i32_no_nl,
	 * then '.' and fractional digits.
	 */
	static byte[] buildPrintF64Core(boolean appendNewline, WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=f64 value (param), 1=i32 is_neg, 2=i32 int_part, 3=f64 frac,
		// 4=i32 digit, 5=i32 digit_count
		w.write(5);
		w.write(1);
		w.write(Type.I32); // local 1: is_neg
		w.write(1);
		w.write(Type.I32); // local 2: int_part
		w.write(1);
		w.write(Type.F64); // local 3: frac
		w.write(1);
		w.write(Type.I32); // local 4: digit
		w.write(1);
		w.write(Type.I32); // local 5: digit_count

		// Check if negative
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1); // is_neg

		// If negative, negate
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);

		// Get integer part: int_part = i32(floor(value))
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_FLOOR);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // int_part

		// If negative, write '-'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		// Print integer part
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);

		// Print '.'
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// Compute fractional part: frac = value - f64(int_part)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // frac

		// digit_count = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(5);

		// Loop to extract fractional digits
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		// frac = frac * 10.0
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// digit = i32(trunc(frac))
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4);

		// Store digit char at OUT_BUF_OFFSET
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// Write the single digit
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// frac = frac - f64(digit)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// digit_count++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(5);

		// Continue if: digit_count < 1 OR (frac > epsilon AND digit_count < 6)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_LT_S);
		// OR
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0000001);
		w.write(Instruction.F64_GT);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);

		w.write(Instruction.BR_IF, 0); // loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// Append newline if needed
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.offset());
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.length());
			w.write(Instruction.CALL);
			w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		}

		w.write(Instruction.END);
		return body.toByteArray();
	}

}
