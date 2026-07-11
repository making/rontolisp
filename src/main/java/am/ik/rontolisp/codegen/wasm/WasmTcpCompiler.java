package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code rontolisp:tcp-connect} / {@code tcp-listen} / {@code tcp-accept} /
 * {@code tcp-local-port} built-ins for the WASM component backend. The heavy lifting (the
 * WASI 0.3 {@code wasi:sockets} plumbing) lives in the sockets adapter's {@code sock.*}
 * imports; this compiler evaluates the arguments and calls them. A successful
 * connect/listen/accept writes a preview1-style socket fd (&gt;= 200) through
 * {@code SOCK_FD_ADDR}, boxed as an i31 integer -- the stream handle: the standard stream
 * built-ins ({@code read-line}, {@code write-line}, {@code read-byte},
 * {@code write-byte}, {@code close}) flow through
 * {@code fd_read}/{@code fd_write}/{@code fd_close}, which the adapter dispatches to the
 * socket's streams for fds &gt;= 200. A call that fails (e.g. connection refused, or the
 * host ran wasmtime without {@code -S tcp=y
 * -S inherit-network=y}) yields {@code nil}, matching the fetch error convention.
 *
 * <p>
 * The tcp built-ins are component-only: in Preview 1 mode they raise a compile error
 * (there is no host {@code wasi:sockets}). The component adapter parses IPv4 literals
 * only; hostname resolution ({@code wasi:sockets/ip-name-lookup}) is not wired yet.
 */
final class WasmTcpCompiler {

	private WasmTcpCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.component) {
			throw new UnsupportedOperationException("rontolisp:" + member
					+ " is only available in WASI component mode (--component), not Preview 1 WASM");
		}
		List<LispVal> args = cons.toList();
		final WasmWriter w = ctx.writer;
		switch (member) {
			case LispNames.TCP_CONNECT -> {
				requireArgs(member, args, 2, 2);
				compileHostPortCall(args.get(1), args.get(2), ctx, WasmLispCompiler.FUNC_TCP_CONNECT);
			}
			case LispNames.TCP_LISTEN -> {
				requireArgs(member, args, 1, 2);
				// (port &optional host); no host binds all interfaces (ptr 0, len 0).
				compileHostPortCall(args.size() == 3 ? args.get(2) : null, args.get(1), ctx,
						WasmLispCompiler.FUNC_TCP_LISTEN);
			}
			case LispNames.TCP_ACCEPT -> {
				requireArgs(member, args, 1, 1);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.castI31GetS(ctx);
				i32(w, WasmLispCompiler.SOCK_FD_ADDR);
				call(w, WasmLispCompiler.FUNC_TCP_ACCEPT);
				emitFdOrNil(w);
			}
			case LispNames.TCP_LOCAL_PORT -> {
				requireArgs(member, args, 1, 1);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.castI31GetS(ctx);
				i32(w, WasmLispCompiler.SOCK_FD_ADDR);
				call(w, WasmLispCompiler.FUNC_TCP_LOCAL_PORT);
				// The port is written through the out-pointer like the fds; a non-zero
				// errno (not a socket handle) yields nil.
				emitFdOrNil(w);
			}
			case LispNames.TCP_LOCAL_ADDRESS, LispNames.TCP_PEER_ADDRESS, LispNames.TCP_PEER_PORT -> {
				// The address/peer accessors are not wired through the sockets adapter:
				// they evaluate the handle and yield nil -- the WASM failure convention
				// (fetch, tcp errors). NOT a compile error: usocket.lisp splices whole,
				// so an unconditional error here would break every usocket program on
				// the component target even when these accessors are never called.
				requireArgs(member, args, 1, 1);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				w.write(Instruction.DROP);
				w.write(Instruction.REF_NULL);
				w.writeHeapType(Type.EQ.code());
			}
			default -> throw new UnsupportedOperationException("Unknown tcp built-in: " + member);
		}
	}

	// Emits sock.tcp-connect / sock.tcp-listen: (hostPtr, hostLen, port, fdOut) ->
	// errno, then boxes the written fd (or yields nil on a non-zero errno). A null
	// host expr passes (0, 0) -- tcp-listen's bind-all default.
	private static void compileHostPortCall(@Nullable LispVal hostExpr, LispVal portExpr, WasmLispCompiler.Ctx ctx,
			int funcIndex) {
		final WasmWriter w = ctx.writer;
		if (hostExpr == null) {
			i32(w, 0);
			i32(w, 0);
		}
		else {
			int hostTmp = ctx.allocTemp();
			WasmExprCompiler.compileExpr(hostExpr, ctx);
			w.write(Instruction.SET_LOCAL);
			w.writeSignedLeb128(hostTmp);
			// The host string's bytes live on the GC heap: copy them into linear scratch
			// at
			// HEAP_PTR (consumed immediately by the sockets call below) and push (ptr,
			// len).
			// ptr = HEAP_PTR + 1 (skip the opening quote) ; len = _str_to_mem(host,
			// HEAP_PTR) - 2 (strip the quotes). i32 intermediates stay on the stack (ctx
			// temps are ref-typed).
			i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
			w.write(Instruction.I32_LOAD, 0x02, 0x00);
			i32(w, 1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(hostTmp);
			i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
			w.write(Instruction.I32_LOAD, 0x02, 0x00);
			WasmEmitHelper.emitStrToMemCall(w);
			i32(w, 2);
			w.write(Instruction.I32_SUB);
		}
		WasmExprCompiler.compileExpr(portExpr, ctx);
		WasmEmitHelper.castI31GetS(ctx);
		i32(w, WasmLispCompiler.SOCK_FD_ADDR);
		call(w, funcIndex);
		emitFdOrNil(w);
	}

	// With the errno on the stack: 0 -> the i31-boxed fd written to SOCK_FD_ADDR,
	// non-zero -> nil.
	private static void emitFdOrNil(WasmWriter w) {
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		i32(w, WasmLispCompiler.SOCK_FD_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
	}

	private static void requireArgs(String member, List<LispVal> args, int min, int max) {
		int given = args.size() - 1;
		if (given < min || given > max) {
			String expected = (min == max) ? String.valueOf(min) : min + " or " + max;
			throw new UnsupportedOperationException(member + " expects " + expected + " arguments, got " + given);
		}
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(func);
	}

}
