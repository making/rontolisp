package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
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
	record HandleMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	/**
	 * Everything the compiler and the directive-site compiler need: the interface to
	 * implement, the handler-funcref field, the {@code serve} entry point, the program
	 * class no-arg construction refs and the injected {@code handle} method body.
	 */
	record HttpHandlerRuntime(ClassEntry handlerInterface, Utf8Entry handlerFieldName, Utf8Entry handlerFieldDesc,
			FieldRefEntry handlerField, MethodRefEntry serve, ClassEntry progClass, MethodRefEntry progInit,
			HandleMethod handle) {
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
	static HttpHandlerRuntime build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectArrayClass,
			MethodRefEntry stringLength, MethodRefEntry stringConcat, boolean bufferBody, boolean hasTr) {
		ClassEntry handlerInterface = cp.classEntry(SUPPORT_CLASS + "$Handler");
		ClassEntry requestClass = cp.classEntry(SUPPORT_CLASS + "$Request");
		ClassEntry supportClass = cp.classEntry(SUPPORT_CLASS);
		ClassEntry runtimeClass = cp.classEntry(RUNTIME_CLASS);
		MethodRefEntry serve = cp.methodRef(supportClass, "serve", "(IL" + SUPPORT_CLASS + "$Handler;)V");

		// The async runtime helpers (forced on whenever http-handler is used): the
		// default request body streams in, the handler's future is awaited, a stream
		// response body drains out.
		MethodRefEntry makeStream = cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.MAKE_STREAM_METHOD,
				JvmAsyncRuntimeBuilder.MAKE_STREAM_DESC);
		MethodRefEntry streamWrite = cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.STREAM_WRITE_METHOD,
				JvmAsyncRuntimeBuilder.STREAM_WRITE_DESC);
		MethodRefEntry streamClose = cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.STREAM_CLOSE_METHOD,
				JvmAsyncRuntimeBuilder.UNARY_DESC);
		MethodRefEntry awaitHelper = cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.AWAIT_METHOD,
				JvmAsyncRuntimeBuilder.AWAIT_DESC);
		MethodRefEntry drainBody = cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.DRAIN_BODY_METHOD,
				JvmAsyncRuntimeBuilder.UNARY_DESC);

		Utf8Entry unaryDesc = cp.utf8Entry(JvmAsyncRuntimeBuilder.UNARY_DESC);
		// The compiled http-server.lisp entry points, called directly by their mangled
		// method names (they are methods of this same generated class; the splice
		// guarantees their presence in every serving program, and the direct call is
		// the shaker-visible edge).
		MethodRefEntry normalizeResponse = cp.methodRef(thisClass,
				cp.utf8Entry(JvmLispCompiler.mangleMethodName(
						PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, ClackEnv.NORMALIZE_RESPONSE))),
				unaryDesc);
		// The request body crosses as OCTETS -- RontoHttpClack.bodyOctets answers
		// the packed byte[] vector -- for both :raw-body modes: the buffered Gray stream
		// is a byte stream and stores them as they are (encoding a decoded body doubled
		// every octet >= #x80 of a binary POST), and the default asynchronous stream is
		// an octet stream on every backend, one settled chunk here.
		MethodRefEntry bodyOctets = cp.methodRef(runtimeClass, "bodyOctets", "(L" + SUPPORT_CLASS + "$Request;)[B");
		MethodRefEntry buildEnv = cp.methodRef(runtimeClass, "buildEnv",
				"(L" + SUPPORT_CLASS + "$Request;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry toResponse = cp.methodRef(runtimeClass, "toResponse",
				"(Ljava/lang/Object;Ljava/lang/Object;)L" + SUPPORT_CLASS + "$Response;");

		Utf8Entry handlerFieldName = cp.utf8Entry("_httpHandlerFn");
		Utf8Entry handlerFieldDesc = cp.utf8Entry("Ljava/lang/Object;");
		FieldRefEntry handlerField = cp.fieldRef(thisClass, handlerFieldName, handlerFieldDesc);
		MethodRefEntry progInit = cp.methodRef(thisClass, "<init>", "()V");
		MethodRefEntry invoke1 = cp.methodRef(thisClass, "_invoke_1",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

		// handle(Request): slots 0 this, 1 request, 2 rawBody, 3 body text scratch /
		// env, 4 result / triple, 5 drained body.
		MethodCode a = new MethodCode();
		if (bufferBody) {
			// rawBody = %http-body-stream(bodyOctets(request)) -- the compiled Gray
			// instance over the octets as they came; the defun itself answers nil for
			// an empty body.
			MethodRefEntry bodyStream = cp.methodRef(thisClass,
					cp.utf8Entry(JvmLispCompiler.mangleMethodName(
							PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, ClackEnv.BODY_STREAM))),
					unaryDesc);
			a.aload(1);
			a.invokestatic(bodyOctets);
			a.invokestatic(bodyStream);
			a.astore(2);
		}
		else {
			// rawBody = the asynchronous stream: one settled octet chunk when the
			// request carries a body, an already-closed empty stream otherwise (its
			// first read observes end of stream) -- interpreter parity.
			a.invokestatic(makeStream);
			a.astore(2);
			a.aload(1);
			a.invokestatic(bodyOctets);
			a.astore(3);
			MethodCode.Label bodyEmpty = a.newLabel();
			a.aload(3);
			a.arraylength();
			a.loadConstant(1);
			a.if_icmple(bodyEmpty); // byte[]{8} alone: no body
			a.aload(2);
			a.aload(3);
			a.invokestatic(streamWrite);
			a.pop();
			a.labelBinding(bodyEmpty);
			a.aload(2);
			a.invokestatic(streamClose);
			a.pop();
		}
		// env = RontoHttpClack.buildEnv(request, rawBody)
		a.aload(1);
		a.aload(2);
		a.invokestatic(buildEnv);
		a.astore(3);
		// result = _await(_invoke_1(_httpHandlerFn, env))
		a.getstatic(handlerField);
		a.aload(3);
		a.invokestatic(invoke1);
		JvmTailBounce.unwrapRaw(a, cp, thisClass, objectArrayClass, hasTr);
		a.invokestatic(awaitHelper);
		// triple = %http-normalize-response(result)
		a.invokestatic(normalizeResponse);
		a.astore(4);
		// drained = _drain_body(third(triple))
		a.aload(4);
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.invokestatic(drainBody);
		a.astore(5);
		// return RontoHttpClack.toResponse(triple, drained)
		a.aload(4);
		a.aload(5);
		a.invokestatic(toResponse);
		a.areturn();

		HandleMethod handle = new HandleMethod(cp.utf8Entry("handle"),
				cp.utf8Entry("(L" + SUPPORT_CLASS + "$Request;)L" + SUPPORT_CLASS + "$Response;"), a);
		return new HttpHandlerRuntime(handlerInterface, handlerFieldName, handlerFieldDesc, handlerField, serve,
				thisClass, progInit, handle);
	}

}
