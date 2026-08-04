package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.ClackEnv;

/**
 * Builds the JVM-backend runtime for the {@code rontolisp:http-handler} directive. The
 * generated program class itself implements
 * {@code am.ik.rontolisp.eval.HttpHandlerSupport.Handler} (the same mechanism as the
 * tls-connect trust-all {@code X509TrustManager}: the backend cannot emit an anonymous
 * class, so the program class takes on the interface), the directive stores the compiled
 * handler funcref in the {@code _httpHandlerFn} static field and calls
 * {@code HttpHandlerSupport.serve(port, new Prog())}.
 *
 * <p>
 * Since the Clack cutover the injected {@code handle(Request)} method is thin glue: a
 * compiled http-handler class is not standalone anyway (it needs the rontolisp jar on the
 * runtime classpath), so the Clack environment is built by
 * {@code HttpHandlerJvmRuntime.buildEnv} -- real Java, the backend's host language -- and
 * the response marshalled back by its {@code toResponse}. The emitted bytecode keeps only
 * what must be bytecode, because it calls into the generated class itself:
 *
 * <ul>
 * <li>the {@code :raw-body} construction -- the default asynchronous stream via the async
 * runtime helpers, or (under {@code :raw-body :buffered}, a compile-time constant scanned
 * off the program by {@code ClackEnv.usesBufferedBody}) the COMPILED
 * {@code %http-body-stream} Gray instance, the same construction the WASM component
 * uses;</li>
 * <li>the handler dispatch through {@code _invoke_1} + {@code _await} (an async-defun
 * handler returns a future; each request runs on its own virtual thread);</li>
 * <li>the direct call to the compiled {@code %http-normalize-response}
 * (http-server.lisp), whose delayed-response arm must {@code funcall} back into compiled
 * code -- a direct INVOKESTATIC, so the {@code --optimize} class shaker sees the edge
 * from its {@code handle} root and keeps the normalizer chain;</li>
 * <li>the {@code _drain_body} pass over the triple's body (a proxied fetch stream body
 * drains to its quoted concatenation).</li>
 * </ul>
 */
final class JvmHttpHandlerRuntimeBuilder {

	/** The internal name of the interpreter-shared HTTP server support class. */
	private static final String SUPPORT_CLASS = "am/ik/rontolisp/eval/HttpHandlerSupport";

	/** The internal name of the JVM-backend Clack glue class. */
	private static final String RUNTIME_CLASS = "am/ik/rontolisp/eval/HttpHandlerJvmRuntime";

	private JvmHttpHandlerRuntimeBuilder() {
	}

	/** The ready-to-emit {@code handle(Request)} method body. */
	record HandleMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	/**
	 * Everything the compiler and the directive-site compiler need: the interface to
	 * implement, the handler-funcref field, the {@code serve} entry point, the program
	 * class no-arg construction refs and the injected {@code handle} method body.
	 */
	record HttpHandlerRuntime(ClassConstant handlerInterface, Utf8Constant handlerFieldName,
			Utf8Constant handlerFieldDesc, FieldrefConstant handlerField, MethodrefConstant serve,
			ClassConstant progClass, MethodrefConstant progInit, HandleMethod handle) {
	}

	/**
	 * Builds the constant-pool entries and the {@code handle} method body.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringLength {@code String.length()}
	 * @param stringConcat {@code String.concat(String)}
	 * @param bufferBody the program's {@code :raw-body} mode (a compile-time constant --
	 * one handler slot, one mode)
	 * @return the runtime refs and method body
	 */
	static HttpHandlerRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectArrayClass,
			MethodrefConstant stringLength, MethodrefConstant stringConcat, boolean bufferBody) {
		ClassConstant handlerInterface = cp.addClass(cp.addUtf8(SUPPORT_CLASS + "$Handler"));
		ClassConstant requestClass = cp.addClass(cp.addUtf8(SUPPORT_CLASS + "$Request"));
		ClassConstant supportClass = cp.addClass(cp.addUtf8(SUPPORT_CLASS));
		ClassConstant runtimeClass = cp.addClass(cp.addUtf8(RUNTIME_CLASS));
		MethodrefConstant serve = cp.addMethodref(supportClass,
				cp.addNameAndType(cp.addUtf8("serve"), cp.addUtf8("(IL" + SUPPORT_CLASS + "$Handler;)V")));

		// The async runtime helpers (forced on whenever http-handler is used): the
		// default request body streams in, the handler's future is awaited, a stream
		// response body drains out.
		MethodrefConstant makeStream = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.MAKE_STREAM_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.MAKE_STREAM_DESC)));
		MethodrefConstant streamWrite = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_WRITE_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_WRITE_DESC)));
		MethodrefConstant streamClose = cp.addMethodref(thisClass, cp.addNameAndType(
				cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_CLOSE_METHOD), cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)));
		MethodrefConstant awaitHelper = cp.addMethodref(thisClass, cp.addNameAndType(
				cp.addUtf8(JvmAsyncRuntimeBuilder.AWAIT_METHOD), cp.addUtf8(JvmAsyncRuntimeBuilder.AWAIT_DESC)));
		MethodrefConstant drainBody = cp.addMethodref(thisClass, cp.addNameAndType(
				cp.addUtf8(JvmAsyncRuntimeBuilder.DRAIN_BODY_METHOD), cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)));

		Utf8Constant unaryDesc = cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC);
		// The compiled http-server.lisp entry points, called directly by their mangled
		// method names (they are methods of this same generated class; the splice
		// guarantees their presence in every serving program, and the direct call is
		// the shaker-visible edge).
		MethodrefConstant normalizeResponse = cp.addMethodref(thisClass,
				cp.addNameAndType(
						cp.addUtf8(JvmLispCompiler.mangleMethodName(
								PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, ClackEnv.NORMALIZE_RESPONSE))),
						unaryDesc));
		MethodrefConstant requestBodyString = cp.addMethodref(requestClass,
				cp.addNameAndType(cp.addUtf8("bodyString"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant buildEnv = cp.addMethodref(runtimeClass, cp.addNameAndType(cp.addUtf8("buildEnv"),
				cp.addUtf8("(L" + SUPPORT_CLASS + "$Request;Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant toResponse = cp.addMethodref(runtimeClass, cp.addNameAndType(cp.addUtf8("toResponse"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)L" + SUPPORT_CLASS + "$Response;")));

		Utf8Constant handlerFieldName = cp.addUtf8("_httpHandlerFn");
		Utf8Constant handlerFieldDesc = cp.addUtf8("Ljava/lang/Object;");
		FieldrefConstant handlerField = cp.addFieldref(thisClass,
				cp.addNameAndType(handlerFieldName, handlerFieldDesc));
		MethodrefConstant progInit = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant invoke1 = cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("_invoke_1"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));

		ConstantPool.StringConstant quote = cp.addString("\"");

		// handle(Request): slots 0 this, 1 request, 2 rawBody, 3 body text scratch /
		// env, 4 result / triple, 5 drained body.
		Asm a = new Asm();
		if (bufferBody) {
			// rawBody = %http-body-stream(quoted(request.bodyString())) -- the compiled
			// Gray instance; the defun itself answers nil for an empty body.
			MethodrefConstant bodyStream = cp.addMethodref(thisClass,
					cp.addNameAndType(
							cp.addUtf8(JvmLispCompiler.mangleMethodName(
									PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, ClackEnv.BODY_STREAM))),
							unaryDesc));
			a.aload(1);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(requestBodyString.index());
			quoteWrap(a, quote, stringConcat);
			a.op(Opcode.INVOKESTATIC);
			a.u2(bodyStream.index());
			a.astore(2);
		}
		else {
			// rawBody = the asynchronous stream: one settled quoted chunk when the
			// request carries a body, an already-closed empty stream otherwise (its
			// first read observes end of stream) -- interpreter parity.
			a.op(Opcode.INVOKESTATIC);
			a.u2(makeStream.index());
			a.astore(2);
			a.aload(1);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(requestBodyString.index());
			a.astore(3);
			int bodyEmpty = a.label();
			a.aload(3);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringLength.index());
			a.branch(Opcode.IFEQ, bodyEmpty);
			a.aload(2);
			a.aload(3);
			quoteWrap(a, quote, stringConcat);
			a.op(Opcode.INVOKESTATIC);
			a.u2(streamWrite.index());
			a.op(Opcode.POP);
			a.bind(bodyEmpty);
			a.aload(2);
			a.op(Opcode.INVOKESTATIC);
			a.u2(streamClose.index());
			a.op(Opcode.POP);
		}
		// env = HttpHandlerJvmRuntime.buildEnv(request, rawBody)
		a.aload(1);
		a.aload(2);
		a.op(Opcode.INVOKESTATIC);
		a.u2(buildEnv.index());
		a.astore(3);
		// result = _await(_invoke_1(_httpHandlerFn, env))
		a.op(Opcode.GETSTATIC);
		a.u2(handlerField.index());
		a.aload(3);
		a.op(Opcode.INVOKESTATIC);
		a.u2(invoke1.index());
		a.op(Opcode.INVOKESTATIC);
		a.u2(awaitHelper.index());
		// triple = %http-normalize-response(result)
		a.op(Opcode.INVOKESTATIC);
		a.u2(normalizeResponse.index());
		a.astore(4);
		// drained = _drain_body(third(triple))
		a.aload(4);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.op(Opcode.INVOKESTATIC);
		a.u2(drainBody.index());
		a.astore(5);
		// return HttpHandlerJvmRuntime.toResponse(triple, drained)
		a.aload(4);
		a.aload(5);
		a.op(Opcode.INVOKESTATIC);
		a.u2(toResponse.index());
		a.areturn();

		HandleMethod handle = new HandleMethod(cp.addUtf8("handle"),
				cp.addUtf8("(L" + SUPPORT_CLASS + "$Request;)L" + SUPPORT_CLASS + "$Response;"), 6, 6, a.finish());
		return new HttpHandlerRuntime(handlerInterface, handlerFieldName, handlerFieldDesc, handlerField, serve,
				thisClass, progInit, handle);
	}

	/** Wraps the String on top of the stack in surrounding quotes. */
	private static void quoteWrap(Asm a, ConstantPool.StringConstant quote, MethodrefConstant stringConcat) {
		a.ldc(quote.index()); // [value, q]
		a.op(Opcode.SWAP); // [q, value]
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(stringConcat.index()); // [q+value]
		a.ldc(quote.index()); // [q+value, q]
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(stringConcat.index()); // [quoted]
	}

	/** Minimal label-based assembler, mirroring the one in JvmFetchRuntimeBuilder. */
	private static final class Asm {

		private final List<Integer> code = new ArrayList<>();

		private final Map<Integer, Integer> labelPos = new HashMap<>();

		private final Map<Integer, List<Integer>> pending = new HashMap<>();

		private int nextLabel = 0;

		int label() {
			return this.nextLabel++;
		}

		void bind(int label) {
			int pos = this.code.size();
			this.labelPos.put(label, pos);
			List<Integer> ps = this.pending.remove(label);
			if (ps != null) {
				for (int bp : ps) {
					JvmRuntimeBuilder.patchBranch(this.code, bp, pos);
				}
			}
		}

		void branch(int opcode, int label) {
			int bp = this.code.size();
			this.code.add(opcode);
			JvmRuntimeBuilder.emitU2(this.code, 0);
			Integer tgt = this.labelPos.get(label);
			if (tgt != null) {
				JvmRuntimeBuilder.patchBranch(this.code, bp, tgt);
			}
			else {
				this.pending.computeIfAbsent(label, k -> new ArrayList<>()).add(bp);
			}
		}

		void op(int opcode) {
			this.code.add(opcode);
		}

		void u2(int value) {
			JvmRuntimeBuilder.emitU2(this.code, value);
		}

		void aload(int slot) {
			this.code.add(Opcode.ALOAD);
			this.code.add(slot);
		}

		void astore(int slot) {
			this.code.add(Opcode.ASTORE);
			this.code.add(slot);
		}

		void aaload() {
			this.code.add(Opcode.AALOAD);
		}

		void iconst(int n) {
			if (n == -1) {
				this.code.add(Opcode.ICONST_M1);
			}
			else if (n >= 0 && n <= 5) {
				this.code.add(Opcode.ICONST_0 + n);
			}
			else if (n >= -128 && n <= 127) {
				this.code.add(Opcode.BIPUSH);
				this.code.add(n & 0xFF);
			}
			else {
				this.code.add(Opcode.SIPUSH);
				JvmRuntimeBuilder.emitU2(this.code, n);
			}
		}

		void ldc(int index) {
			if (index <= 255) {
				this.code.add(Opcode.LDC);
				this.code.add(index);
			}
			else {
				this.code.add(Opcode.LDC_W);
				JvmRuntimeBuilder.emitU2(this.code, index);
			}
		}

		void checkcast(ClassConstant c) {
			this.code.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(this.code, c.index());
		}

		void areturn() {
			this.code.add(Opcode.ARETURN);
		}

		List<Integer> finish() {
			if (!this.pending.isEmpty()) {
				throw new IllegalStateException(
						"Unbound labels in http-handler runtime assembly: " + this.pending.keySet());
			}
			return this.code;
		}

	}

}
