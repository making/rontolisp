package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.ClackEnv;

/**
 * Builds the JVM-backend runtime for the {@code rontolisp:http-handler} directive. The
 * generated program class itself implements
 * {@code am.ik.rontolisp.runtime.RontoHttpServer.Handler} (the same mechanism as the
 * tls-connect trust-all {@code X509TrustManager}: the backend cannot emit an anonymous
 * class, so the program class takes on the interface), the directive stores the compiled
 * handler funcref in the {@code _httpHandlerFn} static field and calls
 * {@code RontoHttpServer.serve(port, new Prog())}.
 *
 * <p>
 * Since the Clack cutover the injected {@code handle(Request)} method is thin glue: a
 * compiled http-handler class is not standalone anyway (it needs the rontolisp jar on the
 * runtime classpath), so the Clack environment is built by
 * {@code RontoHttpClack.buildEnv} -- real Java, the backend's host language -- and the
 * response marshalled back by its {@code toResponse}. The emitted bytecode keeps only
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
	private static final String SUPPORT_CLASS = "am/ik/rontolisp/runtime/RontoHttpServer";

	/** The internal name of the JVM-backend Clack glue class. */
	private static final String RUNTIME_CLASS = "am/ik/rontolisp/runtime/RontoHttpClack";

	private JvmHttpHandlerRuntimeBuilder() {
	}

	/**
	 * The class files of {@code am.ik.rontolisp.runtime} that travel BESIDE a compiled
	 * program that serves -- {@link #SUPPORT_CLASS} and {@link #RUNTIME_CLASS} with their
	 * nested types, plus the two declarations they read (the environment key set and the
	 * hash-table shape). This is the whole runtime closure of an emitted
	 * {@code handle(Request)}: those classes import nothing but {@code java.base} and
	 * {@code jdk.httpserver}, which is what lets a served program run on a bare
	 * {@code java -cp .} instead of needing the rontolisp jar on its classpath.
	 *
	 * <p>
	 * The list must follow the package: a class added to the closure and not added here
	 * is a {@code NoClassDefFoundError} in the consumer, not an error at compile time --
	 * which is why {@code JvmHttpHandlerTravellingRuntimeTest} recomputes the closure
	 * from the emitted class and fails when the two disagree.
	 */
	static final List<String> RUNTIME_CLASS_FILES = List.of("am/ik/rontolisp/runtime/RontoHttpServer.class",
			"am/ik/rontolisp/runtime/RontoHttpServer$Handler.class",
			"am/ik/rontolisp/runtime/RontoHttpServer$Header.class",
			"am/ik/rontolisp/runtime/RontoHttpServer$Request.class",
			"am/ik/rontolisp/runtime/RontoHttpServer$Response.class",
			"am/ik/rontolisp/runtime/RontoHttpServer$ServerException.class",
			"am/ik/rontolisp/runtime/RontoHttpServer$StoppableServer.class",
			"am/ik/rontolisp/runtime/RontoHttpClack.class", "am/ik/rontolisp/runtime/RontoClackEnv.class",
			"am/ik/rontolisp/runtime/RontoHashTable.class");

	/**
	 * Reads {@link #RUNTIME_CLASS_FILES} off the compiler's own classpath.
	 * @return each class file's path within an output tree (or jar), mapped to its bytes
	 */
	static Map<String, byte[]> runtimeClassFiles() {
		return JvmRuntimeClassFiles.read(RUNTIME_CLASS_FILES);
	}

	/**
	 * The THIRD travelling list ({@code .kb/jvm-export.md}, "What travels"): the servlet
	 * transport a {@code -o app.war} output carries in addition to
	 * {@link #RUNTIME_CLASS_FILES}. Reached ONLY by a war compile
	 * ({@code JvmLispCompiler.servlet}), so no {@code .class} or {@code .jar} output ever
	 * gains these classes' {@code jakarta.servlet} reference -- the one sanctioned
	 * exception to the runtime package importing nothing outside {@code java.base},
	 * satisfied by definition: a war runs in a servlet container, and a container without
	 * {@code jakarta.servlet} is not a container.
	 */
	static final List<String> WAR_RUNTIME_CLASS_FILES = List.of("am/ik/rontolisp/runtime/RontoHttpServlet.class",
			"am/ik/rontolisp/runtime/RontoHttpServletInitializer.class");

	/**
	 * Reads {@link #WAR_RUNTIME_CLASS_FILES} off the compiler's own classpath.
	 * @return each class file's path within the war's {@code WEB-INF/classes}, mapped to
	 * its bytes
	 */
	static Map<String, byte[]> warRuntimeClassFiles() {
		return JvmRuntimeClassFiles.read(WAR_RUNTIME_CLASS_FILES);
	}

	/** The ready-to-emit {@code handle(Request)} method body. */
	record HandleMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
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
		// The request body crosses as OCTETS -- RontoHttpClack.bodyOctets answers
		// the packed byte[] vector -- for both :raw-body modes: the buffered Gray stream
		// is a byte stream and stores them as they are (encoding a decoded body doubled
		// every octet >= #x80 of a binary POST), and the default asynchronous stream is
		// an octet stream on every backend, one settled chunk here.
		MethodrefConstant bodyOctets = cp.addMethodref(runtimeClass,
				cp.addNameAndType(cp.addUtf8("bodyOctets"), cp.addUtf8("(L" + SUPPORT_CLASS + "$Request;)[B")));
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

		// handle(Request): slots 0 this, 1 request, 2 rawBody, 3 body text scratch /
		// env, 4 result / triple, 5 drained body.
		MethodCode a = new MethodCode();
		if (bufferBody) {
			// rawBody = %http-body-stream(bodyOctets(request)) -- the compiled Gray
			// instance over the octets as they came; the defun itself answers nil for
			// an empty body.
			MethodrefConstant bodyStream = cp.addMethodref(thisClass,
					cp.addNameAndType(
							cp.addUtf8(JvmLispCompiler.mangleMethodName(
									PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, ClackEnv.BODY_STREAM))),
							unaryDesc));
			a.aload(1);
			a.invokestatic(bodyOctets.entry());
			a.invokestatic(bodyStream.entry());
			a.astore(2);
		}
		else {
			// rawBody = the asynchronous stream: one settled octet chunk when the
			// request carries a body, an already-closed empty stream otherwise (its
			// first read observes end of stream) -- interpreter parity.
			a.invokestatic(makeStream.entry());
			a.astore(2);
			a.aload(1);
			a.invokestatic(bodyOctets.entry());
			a.astore(3);
			MethodCode.Label bodyEmpty = a.newLabel();
			a.aload(3);
			a.arraylength();
			a.loadConstant(1);
			a.if_icmple(bodyEmpty); // byte[]{8} alone: no body
			a.aload(2);
			a.aload(3);
			a.invokestatic(streamWrite.entry());
			a.pop();
			a.labelBinding(bodyEmpty);
			a.aload(2);
			a.invokestatic(streamClose.entry());
			a.pop();
		}
		// env = RontoHttpClack.buildEnv(request, rawBody)
		a.aload(1);
		a.aload(2);
		a.invokestatic(buildEnv.entry());
		a.astore(3);
		// result = _await(_invoke_1(_httpHandlerFn, env))
		a.getstatic(handlerField.entry());
		a.aload(3);
		a.invokestatic(invoke1.entry());
		a.invokestatic(awaitHelper.entry());
		// triple = %http-normalize-response(result)
		a.invokestatic(normalizeResponse.entry());
		a.astore(4);
		// drained = _drain_body(third(triple))
		a.aload(4);
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(1);
		a.aaload();
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(1);
		a.aaload();
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(0);
		a.aaload();
		a.invokestatic(drainBody.entry());
		a.astore(5);
		// return RontoHttpClack.toResponse(triple, drained)
		a.aload(4);
		a.aload(5);
		a.invokestatic(toResponse.entry());
		a.areturn();

		HandleMethod handle = new HandleMethod(cp.addUtf8("handle"),
				cp.addUtf8("(L" + SUPPORT_CLASS + "$Request;)L" + SUPPORT_CLASS + "$Response;"), a);
		return new HttpHandlerRuntime(handlerInterface, handlerFieldName, handlerFieldDesc, handlerField, serve,
				thisClass, progInit, handle);
	}

}
