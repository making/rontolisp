package am.ik.femtolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.femtolisp.LispCons;
import am.ik.femtolisp.LispInteger;
import am.ik.femtolisp.LispNil;
import am.ik.femtolisp.LispSymbol;
import am.ik.femtolisp.LispTrue;
import am.ik.femtolisp.LispVal;
import am.ik.femtolisp.compiler.LispCompiler;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Compiles Lisp expressions to WASM binary with wasm-GC and WASI Preview 1. All Lisp
 * values are represented as (ref eq) on the stack: integers use i31ref, nil uses ref.null
 * eq.
 */
public final class WasmLispCompiler implements LispCompiler {

	// Function indices (imports come first)
	private static final int FUNC_FD_WRITE = 0; // imported

	private static final int FUNC_START = 1; // _start

	private static final int FUNC_PRINT_I32 = 2; // print_i32 helper

	// Type indices in the rec group
	private static final int TYPE_FD_WRITE = 0;

	private static final int TYPE_START = 1;

	private static final int TYPE_PRINT_I32 = 2;

	private static final int TYPE_CONS = 3;

	// First type/function index for user-defined functions
	private static final int TYPE_USER_BASE = 4;

	private static final int FUNC_USER_BASE = 3;

	// Memory layout for print_i32 helper
	private static final int PRINT_BUF_OFFSET = 0; // 32 bytes for digit buffer

	private static final int IOV_OFFSET = 32; // iov struct at offset 32

	private static final int NWRITTEN_OFFSET = 48; // nwritten at offset 48

	@Override
	public byte[] compile(List<LispVal> program) {
		// Pass 1: Collect defun declarations and top-level expressions
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "defun".equals(sym.name())) {
				List<LispVal> parts = cons.toList();
				String funcName = ((LispSymbol) parts.get(1)).name();
				LispVal paramsVal = parts.get(2);
				List<String> paramNames;
				if (paramsVal instanceof LispNil) {
					paramNames = List.of();
				}
				else {
					paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
				}
				defuns.add(new DefunDecl(funcName, paramNames, parts.subList(3, parts.size())));
			}
			else {
				topLevelExprs.add(expr);
			}
		}

		// Build function info map
		Map<String, WasmFunctionInfo> functions = new HashMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			DefunDecl defun = defuns.get(i);
			functions.put(defun.name,
					new WasmFunctionInfo(defun.name, defun.paramNames.size(), TYPE_USER_BASE + i, FUNC_USER_BASE + i));
		}

		// Pass 2a: Compile each defun body
		List<byte[]> userFunctionBodies = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			ByteArrayOutputStream funcBody = new ByteArrayOutputStream();
			WasmWriter funcWriter = new WasmWriter(funcBody);
			Ctx funcCtx = new Ctx(funcWriter);
			funcCtx.functions = functions;

			// Parameters are the first N locals
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i);
			}
			funcCtx.nextLocal = defun.paramNames.size();

			// Compile body
			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcWriter.write(Instruction.DROP);
				}
				compileExpr(defun.bodyExprs.get(i), funcCtx);
			}
			funcWriter.write(Instruction.END);

			// Rebuild with correct local declarations (only extra locals beyond params)
			ByteArrayOutputStream finalFuncBody = new ByteArrayOutputStream();
			WasmWriter finalFuncWriter = new WasmWriter(finalFuncBody);
			int extraLocals = funcCtx.nextLocal - defun.paramNames.size();
			if (extraLocals > 0) {
				finalFuncWriter.write(1); // 1 local group
				finalFuncWriter.write(extraLocals);
				finalFuncWriter.write(Type.REFNULL.code());
				finalFuncWriter.writeHeapType(Type.EQ.code());
			}
			else {
				finalFuncWriter.write(0); // 0 local groups
			}
			finalFuncWriter.write((Object) funcBody.toByteArray());
			userFunctionBodies.add(finalFuncBody.toByteArray());
		}

		// Pass 2b: Build _start function body
		ByteArrayOutputStream startBody = new ByteArrayOutputStream();
		WasmWriter startWriter = new WasmWriter(startBody);
		Ctx ctx = new Ctx(startWriter);
		ctx.functions = functions;

		for (LispVal expr : topLevelExprs) {
			compileExpr(expr, ctx);
			startWriter.write(Instruction.DROP);
		}
		startWriter.write(Instruction.END);

		// Rebuild start body with correct local count
		ByteArrayOutputStream finalStartBody = new ByteArrayOutputStream();
		WasmWriter finalStartWriter = new WasmWriter(finalStartBody);
		int numLocals = ctx.nextLocal;
		if (numLocals > 0) {
			finalStartWriter.write(1); // 1 local group
			finalStartWriter.write(numLocals); // count
			// type: (ref null eq)
			finalStartWriter.write(Type.REFNULL.code());
			finalStartWriter.writeHeapType(Type.EQ.code());
		}
		else {
			finalStartWriter.write(0); // 0 local groups
		}
		finalStartWriter.write((Object) startBody.toByteArray());

		// Build print_i32 helper function body
		byte[] printI32Body = buildPrintI32Body();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter mainWriter = new WasmWriter(out);
		mainWriter //
			.write("\0asm") // magic
			.writeLittleEndian4(1) // version
			// Type section: plain func types + rec group for struct
			.writeTypeSection(types -> {
				// type 0: fd_write (i32, i32, i32, i32) -> (i32) - plain func
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 1: _start () -> () - plain func
				types.addFunc(new Type[] {}, new Type[] {});
				// type 2: print_i32 (i32) -> () - plain func
				types.addFunc(new Type[] { Type.I32 }, new Type[] {});
				// type 3: cons struct (future use) - in rec group for recursive types
				types.addRecGroup(rec -> {
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
				});
				// type 4+: user-defined function types
				for (DefunDecl defun : defuns) {
					int paramCount = defun.paramNames.size();
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(paramCount);
						for (int i = 0; i < paramCount; i++) {
							w.write(Type.REFNULL.code());
							w.writeHeapType(Type.EQ.code());
						}
						w.write(1); // 1 result
						w.write(Type.REFNULL.code());
						w.writeHeapType(Type.EQ.code());
					});
				}
			})
			// Import section
			.writeImportSection(imports -> imports.addImport("wasi_snapshot_preview1", "fd_write",
					ExternalKind.FUNCTION, TYPE_FD_WRITE))
			// Function section
			.writeFunction(fnDef -> {
				fnDef.addFunction(TYPE_START).addFunction(TYPE_PRINT_I32);
				for (DefunDecl defun : defuns) {
					WasmFunctionInfo fi = java.util.Objects.requireNonNull(functions.get(defun.name));
					fnDef.addFunction(fi.typeIndex);
				}
			})
			// Memory section
			.writeMemory(memories -> memories.addMemory(1))
			// Export section
			.writeExport(exports -> exports.addExport("memory", ExternalKind.MEMORY, 0)
				.addExport("_start", ExternalKind.FUNCTION, FUNC_START))
			// Code section
			.writeCode(code -> {
				code.addFunction(finalStartBody.toByteArray()).addFunction(printI32Body);
				for (byte[] body : userFunctionBodies) {
					code.addFunction(body);
				}
			});
		return out.toByteArray();
	}

	private void compileExpr(LispVal expr, Ctx ctx) {
		switch (expr) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNil ignored -> {
				// ref.null eq
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> {
				// i31ref with value 1
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	private void compileSymbolRef(LispSymbol sym, Ctx ctx) {
		Integer slot = ctx.locals.get(sym.name());
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile symbol: " + sym.name());
		}
	}

	private void compileCons(LispCons cons, Ctx ctx) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "+" -> compileArith(cons, ctx, Instruction.I32_ADD);
				case "-" -> compileArith(cons, ctx, Instruction.I32_SUB);
				case "*" -> compileArith(cons, ctx, Instruction.I32_MUL);
				case "/" -> compileArith(cons, ctx, Instruction.I32_DIV_S);
				case "mod" -> compileArith(cons, ctx, Instruction.I32_REM_S);
				case "=" -> compileComparison(cons, ctx, Instruction.I32_EQ);
				case "<" -> compileComparison(cons, ctx, Instruction.I32_LT_S);
				case ">" -> compileComparison(cons, ctx, Instruction.I32_GT_S);
				case "<=" -> compileComparison(cons, ctx, Instruction.I32_LE_S);
				case ">=" -> compileComparison(cons, ctx, Instruction.I32_GE_S);
				case "print" -> compilePrint(cons, ctx);
				case "if" -> compileIf(cons, ctx);
				case "let" -> compileLet(cons, ctx);
				case "progn" -> compileProgn(cons, ctx);
				case "defun" -> {
					// defun at non-top-level is a no-op (already processed in pass 1)
					ctx.writer.write(Instruction.REF_NULL);
					ctx.writer.writeHeapType(Type.EQ.code());
				}
				default -> compileFunctionCall(sym.name(), cons, ctx);
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			compileLambdaCall(headCons, cons, ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

	private void compileArith(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		castI31GetS(ctx);
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
			castI31GetS(ctx);
			ctx.writer.write(opcode);
		}
		// Box result back to i31ref
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private void compileComparison(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		castI31GetS(ctx);
		compileExpr(args.get(2), ctx);
		castI31GetS(ctx);
		ctx.writer.write(opcode);
		// Result is i32 (0 or 1). Convert to nil/non-nil for if-form compatibility:
		// if nonzero -> i31ref(1) (truthy), if zero -> ref.null eq (nil/falsy)
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// True: push i31ref(1)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		// False: push nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	private void compilePrint(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		castI31GetS(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(FUNC_PRINT_I32);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	private void compileIf(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// Compile condition
		compileExpr(parts.get(1), ctx);
		// ref.is_null -> true if nil
		ctx.writer.write(Instruction.REF_IS_NULL);
		// if (result (ref eq)) ... else ... end
		ctx.writer.write(Instruction.IF);
		// Block type: (ref eq) - encoded as a type index using signed LEB128
		// We use an inline type: refnull eq
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// If condition is null (nil) -> else branch is "then" (ref.is_null inverts)
		// So: if ref_is_null -> compile else first
		if (parts.size() > 3) {
			compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.ELSE);
		compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.END);
	}

	private void compileLet(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				compileExpr(pairList.get(1), ctx);
				int slot = ctx.allocLocal(name);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}
		ctx.locals = savedLocals;
	}

	private void compileProgn(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}
	}

	private void compileFunctionCall(String name, LispCons cons, Ctx ctx) {
		WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			for (int i = 1; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(fi.funcIndex);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private void compileLambdaCall(LispCons lambda, LispCons call, Ctx ctx) {
		List<LispVal> lambdaParts = lambda.toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		List<LispVal> bodyExprs = lambdaParts.subList(2, lambdaParts.size());
		List<LispVal> callArgs = call.toList();

		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);

		// Evaluate arguments and store in local variables
		for (int i = 0; i < paramNames.size(); i++) {
			compileExpr(callArgs.get(i + 1), ctx);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}

		// Compile body
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(bodyExprs.get(i), ctx);
		}

		ctx.locals = savedLocals;
		// Note: nextLocal is NOT restored because WASM requires local count to be
		// declared upfront
	}

	private void castI31GetS(Ctx ctx) {
		// ref.cast i31
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		// i31.get_s
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	/**
	 * Builds the print_i32 helper function that converts an i32 to decimal string and
	 * writes to stdout via fd_write.
	 */
	private byte[] buildPrintI32Body() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: param 0 = value (i32)
		// local 1 = is_negative (i32)
		// local 2 = digit_count (i32)
		// local 3 = temp (i32)
		w.write(3); // 3 local groups
		w.write(1); // 1 local of type i32 (is_negative)
		w.write(Type.I32);
		w.write(1); // 1 local of type i32 (digit_count)
		w.write(Type.I32);
		w.write(1); // 1 local of type i32 (temp)
		w.write(Type.I32);

		// Check if value is negative
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1); // is_negative

		// If negative, negate
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative
		w.write(Instruction.IF, 0x40); // if void
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0); // value = -value
		w.write(Instruction.END);

		// Handle zero case
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40); // if void
		// Store '0' at buffer
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_STORE8, 0x00, 0x00); // align=0, offset=0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // digit_count = 1
		w.write(Instruction.ELSE);

		// Extract digits (reverse order) into buffer
		w.write(Instruction.BLOCK, 0x40); // block void
		w.write(Instruction.LOOP, 0x40); // loop void
		// if value == 0, break
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1); // br_if to block (exit loop)

		// digit = value % 10
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // temp = digit

		// Store digit as ASCII at buffer[digit_count]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3); // temp (digit)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// digit_count++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		// value /= 10
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);

		w.write(Instruction.BR, 0); // continue loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		w.write(Instruction.END); // end if (zero case else)

		// === Simpler output strategy: copy reversed digits to output area ===
		// Output buffer at offset 64
		int outBuf = 64;

		// If negative, write '-' first
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(outBuf);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(45); // '-'
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.END);

		// Copy digits in reverse from print buffer to output buffer
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // i = 0

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		// outBuf[is_negative + i] = printBuf[digit_count - 1 - i]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(outBuf);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative (0 or 1)
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3); // i
		w.write(Instruction.I32_ADD);

		// Load printBuf[digit_count - 1 - i]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3); // i
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// i++
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

		// Append newline
		// outBuf[is_negative + digit_count] = '\n'
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(outBuf);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10); // '\n'
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// Build iov: {iov_base = outBuf, iov_len = is_negative + digit_count + 1}
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(outBuf);
		w.write(Instruction.I32_STORE, 0x02, 0x00); // align=2, offset=0

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // for newline
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// fd_write(1, iov_offset, 1, nwritten_offset)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // stdout
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // iovs_len
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	private record DefunDecl(String name, List<String> paramNames, List<LispVal> bodyExprs) {
	}

	private record WasmFunctionInfo(String name, int paramCount, int typeIndex, int funcIndex) {
	}

	private static final class Ctx {

		final WasmWriter writer;

		Map<String, Integer> locals = new HashMap<>();

		Map<String, WasmFunctionInfo> functions = Map.of();

		int nextLocal = 0;

		Ctx(WasmWriter writer) {
			this.writer = writer;
		}

		int allocLocal(String name) {
			int slot = this.nextLocal++;
			this.locals.put(name, slot);
			return slot;
		}

	}

}
