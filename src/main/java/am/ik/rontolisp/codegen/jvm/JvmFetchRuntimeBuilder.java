package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.compiler.FetchResponseShape;

/**
 * Builds the JVM bytecode for the {@code rontolisp:fetch} built-in, emitted as a
 * {@code private static} method into the generated standalone {@code .class}.
 * {@code _fetch(Object url, Object options)} <em>starts</em> an outgoing HTTP request
 * (JavaScript {@code fetch}-style) via {@link java.net.http.HttpClient#sendAsync} and
 * returns the future itself -- a first-class rontolisp future the generic {@code _await}
 * resolver ({@code JvmAsyncRuntimeBuilder}) converts into the response property list when
 * awaited. A failed request propagates from {@code join()} as a
 * {@code CompletionException} -- the JavaScript await-rejection timing.
 *
 * <p>
 * The optional {@code options} argument is a property list; {@code :method} (default
 * {@code "GET"}; one of GET/HEAD/POST/PUT/DELETE/OPTIONS/PATCH, matched
 * case-insensitively and sent in canonical upper case), {@code :headers} (a
 * request-header alist) and {@code :body} (a request body string) are recognized. A
 * request whose headers name no user-agent gets
 * {@link FetchResponseShape#defaultUserAgent()} baked in at codegen time -- set
 * explicitly, so the JDK does not write its own {@code Java-http-client/<jdk>} and the
 * request matches the other backends. The methods are only emitted when the program
 * actually uses {@code rontolisp:fetch} or {@code rontolisp:await}.
 */
final class JvmFetchRuntimeBuilder {

	/** The emitted {@code _fetch} method name. */
	static final String METHOD_NAME = "_fetch";

	/** The {@code _fetch} method descriptor. */
	static final String METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private JvmFetchRuntimeBuilder() {
	}

	/** A ready-to-emit method body. */
	record FetchMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	/**
	 * The emitted method body ({@code _fetch}; {@code _await} lives in the async
	 * runtime).
	 */
	record FetchRuntime(FetchMethod fetch) {
	}

	/**
	 * Builds the {@code _fetch} method body and the constant-pool entries it references.
	 * @param cp the constant pool
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringClass {@code java/lang/String}
	 * @param stringLength {@code String.length()}
	 * @param stringSubstring {@code String.substring(II)}
	 * @return the method body
	 */
	static FetchRuntime build(ConstantPool cp, ClassConstant objectArrayClass, ClassConstant stringClass,
			MethodrefConstant stringLength, MethodrefConstant stringSubstring) {
		// --- Interface / class references for the JDK HTTP client ---
		ClassConstant uriClass = cp.addClass(cp.addUtf8("java/net/URI"));
		MethodrefConstant uriCreate = cp.addMethodref(uriClass,
				cp.addNameAndType(cp.addUtf8("create"), cp.addUtf8("(Ljava/lang/String;)Ljava/net/URI;")));

		ClassConstant httpClientClass = cp.addClass(cp.addUtf8("java/net/http/HttpClient"));
		MethodrefConstant newHttpClient = cp.addMethodref(httpClientClass,
				cp.addNameAndType(cp.addUtf8("newHttpClient"), cp.addUtf8("()Ljava/net/http/HttpClient;")));
		MethodrefConstant clientSendAsync = cp
			.addMethodref(httpClientClass, cp.addNameAndType(cp.addUtf8("sendAsync"), cp.addUtf8(
					"(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/util/concurrent/CompletableFuture;")));

		ClassConstant httpRequestClass = cp.addClass(cp.addUtf8("java/net/http/HttpRequest"));
		MethodrefConstant newBuilder = cp.addMethodref(httpRequestClass, cp.addNameAndType(cp.addUtf8("newBuilder"),
				cp.addUtf8("(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;")));

		ClassConstant builderClass = cp.addClass(cp.addUtf8("java/net/http/HttpRequest$Builder"));
		MethodrefConstant builderHeader = cp.addInterfaceMethodref(builderClass, cp.addNameAndType(cp.addUtf8("header"),
				cp.addUtf8("(Ljava/lang/String;Ljava/lang/String;)Ljava/net/http/HttpRequest$Builder;")));
		MethodrefConstant builderBuild = cp.addInterfaceMethodref(builderClass,
				cp.addNameAndType(cp.addUtf8("build"), cp.addUtf8("()Ljava/net/http/HttpRequest;")));

		ClassConstant bodyHandlersClass = cp.addClass(cp.addUtf8("java/net/http/HttpResponse$BodyHandlers"));
		MethodrefConstant ofString = cp.addMethodref(bodyHandlersClass,
				cp.addNameAndType(cp.addUtf8("ofString"), cp.addUtf8("()Ljava/net/http/HttpResponse$BodyHandler;")));

		// Request body publishers and the Builder.method(name, publisher) accessor, used
		// to
		// set the request method and (optional) body.
		MethodrefConstant builderMethod = cp
			.addInterfaceMethodref(builderClass, cp.addNameAndType(cp.addUtf8("method"), cp.addUtf8(
					"(Ljava/lang/String;Ljava/net/http/HttpRequest$BodyPublisher;)Ljava/net/http/HttpRequest$Builder;")));
		ClassConstant bodyPublishersClass = cp.addClass(cp.addUtf8("java/net/http/HttpRequest$BodyPublishers"));
		MethodrefConstant publisherOfString = cp.addMethodref(bodyPublishersClass, cp.addNameAndType(
				cp.addUtf8("ofString"), cp.addUtf8("(Ljava/lang/String;)Ljava/net/http/HttpRequest$BodyPublisher;")));
		MethodrefConstant publisherNoBody = cp.addMethodref(bodyPublishersClass,
				cp.addNameAndType(cp.addUtf8("noBody"), cp.addUtf8("()Ljava/net/http/HttpRequest$BodyPublisher;")));

		MethodrefConstant stringEquals = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant stringEqualsIgnoreCase = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equalsIgnoreCase"), cp.addUtf8("(Ljava/lang/String;)Z")));

		ClassConstant runtimeExceptionClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant runtimeExceptionInit = cp.addMethodref(runtimeExceptionClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));

		ConstantPool.StringConstant userAgentName = cp.addString(FetchResponseShape.USER_AGENT_HEADER);
		ConstantPool.StringConstant userAgentValue = cp.addString(FetchResponseShape.defaultUserAgent());

		ConstantPool.StringConstant methodKey = cp.addString(":method");
		ConstantPool.StringConstant headersKey = cp.addString(":headers");
		ConstantPool.StringConstant bodyKey = cp.addString(":body");
		ConstantPool.StringConstant unsupportedMsg = cp.addString("fetch: unsupported method");
		// The supported HTTP methods, in canonical (upper-case) form. The request is sent
		// with the canonical spelling regardless of the case the caller used.
		String[] methods = { "GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH" };
		ConstantPool.StringConstant[] methodConsts = new ConstantPool.StringConstant[methods.length];
		for (int i = 0; i < methods.length; i++) {
			methodConsts[i] = cp.addString(methods[i]);
		}

		// Local slots: 0 url, 1 options, 2 builder, 3 cursor, 9 request headers,
		// 10 method value, 11 plist cursor, 15 request-body value,
		// 16 canonical method (String), 17 method scratch (unquoted String),
		// 18 body publisher, 19 user-agent seen (null = the caller set none).
		Asm a = new Asm();

		// --- options parsing: method (10), request headers (9), request body (15) ---
		// Keyword-argument matching is case-insensitive (the reader upcases source
		// keywords to :METHOD/:HEADERS/:BODY).
		emitPlistGet(a, methodKey, 11, 10, objectArrayClass, stringClass, stringEqualsIgnoreCase);
		emitPlistGet(a, headersKey, 11, 9, objectArrayClass, stringClass, stringEqualsIgnoreCase);
		emitPlistGet(a, bodyKey, 11, 15, objectArrayClass, stringClass, stringEqualsIgnoreCase);

		// --- resolve the canonical method into slot 16: nil defaults to GET, otherwise
		// it
		// must match one of the supported methods (case-insensitively). ---
		int methodDone = a.label();
		a.aload(10);
		int methodGiven = a.label();
		a.branch(Opcode.IFNONNULL, methodGiven);
		a.ldc(methodConsts[0].index()); // "GET"
		a.astore(16);
		a.branch(Opcode.GOTO, methodDone);
		a.bind(methodGiven);
		a.aload(10);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring); // [methodStr]
		a.astore(17);
		for (ConstantPool.StringConstant m : methodConsts) {
			int next = a.label();
			a.aload(17);
			a.ldc(m.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringEqualsIgnoreCase.index()); // [bool]
			a.branch(Opcode.IFEQ, next);
			a.ldc(m.index());
			a.astore(16);
			a.branch(Opcode.GOTO, methodDone);
			a.bind(next);
		}
		// none matched: throw new RuntimeException("fetch: unsupported method")
		a.op(Opcode.NEW);
		a.u2(runtimeExceptionClass.index());
		a.op(Opcode.DUP);
		a.ldc(unsupportedMsg.index());
		a.op(Opcode.INVOKESPECIAL);
		a.u2(runtimeExceptionInit.index());
		a.op(Opcode.ATHROW);
		a.bind(methodDone);

		// --- resolve the request-body publisher into slot 18: nil -> noBody(), otherwise
		// ofString(stripQuotes(body)). ---
		int bodyDone = a.label();
		a.aload(15);
		int bodyGiven = a.label();
		a.branch(Opcode.IFNONNULL, bodyGiven);
		a.op(Opcode.INVOKESTATIC);
		a.u2(publisherNoBody.index()); // [publisher]
		a.astore(18);
		a.branch(Opcode.GOTO, bodyDone);
		a.bind(bodyGiven);
		a.aload(15);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring); // [bodyStr]
		a.op(Opcode.INVOKESTATIC);
		a.u2(publisherOfString.index()); // [publisher]
		a.astore(18);
		a.bind(bodyDone);

		// builder = HttpRequest.newBuilder(URI.create(stripQuotes(url)))
		stripQuotes(a, 0, stringClass, stringLength, stringSubstring); // [name]
		a.op(Opcode.INVOKESTATIC);
		a.u2(uriCreate.index()); // [uri]
		a.op(Opcode.INVOKESTATIC);
		a.u2(newBuilder.index()); // [builder]
		a.astore(2);

		// Scan the request-header alist (slot 9) for a caller-supplied user-agent, in any
		// spelling (HTTP field names are case-insensitive), into slot 19. A separate pass
		// because the setting loop below cannot answer the question until it has seen the
		// LAST pair, and the default has to be decided before any header is set.
		a.aconstNull();
		a.astore(19);
		a.aload(9);
		a.astore(3); // cursor = request headers
		int uaLoop = a.label();
		int uaEnd = a.label();
		a.bind(uaLoop);
		a.aload(3);
		a.branch(Opcode.IFNULL, uaEnd);
		a.aload(3);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload(); // [pair]
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload(); // [name]
		stripQuotesValue(a, stringClass, stringLength, stringSubstring); // [name']
		a.ldc(userAgentName.index());
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(stringEqualsIgnoreCase.index()); // [bool]
		int uaNext = a.label();
		a.branch(Opcode.IFEQ, uaNext);
		a.ldc(userAgentName.index());
		a.astore(19); // seen (any non-null marks it)
		a.branch(Opcode.GOTO, uaEnd);
		a.bind(uaNext);
		a.aload(3);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(3);
		a.branch(Opcode.GOTO, uaLoop);
		a.bind(uaEnd);

		// Iterate the request-header alist (slot 9) and set each header.
		a.aload(9);
		a.astore(3); // cursor = request headers
		int hLoop = a.label();
		int hEnd = a.label();
		a.bind(hLoop);
		a.aload(3);
		a.branch(Opcode.IFNULL, hEnd); // while cursor != null
		// pair = ((Object[]) cursor)[0]
		a.aload(3);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload(); // [pair]
		a.checkcast(objectArrayClass); // [pair[]]
		a.op(Opcode.DUP); // [pair, pair]
		a.iconst(0);
		a.aaload(); // [pair, name]
		stripQuotesValue(a, stringClass, stringLength, stringSubstring); // [pair, name']
		a.op(Opcode.SWAP); // [name', pair]
		a.iconst(1);
		a.aaload(); // [name', value]
		stripQuotesValue(a, stringClass, stringLength, stringSubstring); // [name',
																			// value']
		a.aload(2); // [name', value', builder]
		a.op(Opcode.DUP_X2); // [builder, name', value', builder]
		a.op(Opcode.POP); // [builder, name', value']
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(builderHeader.index());
		a.op(3); // count: this + 2 args
		a.op(0);
		a.op(Opcode.POP); // discard returned builder
		// cursor = ((Object[]) cursor)[1]
		a.aload(3);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(3);
		a.branch(Opcode.GOTO, hLoop);
		a.bind(hEnd);

		// A caller-silent request carries rontolisp's own user-agent rather than the
		// JDK's Java-http-client/<jdk>: the same request on every backend.
		int uaDone = a.label();
		a.aload(19);
		a.branch(Opcode.IFNONNULL, uaDone);
		a.aload(2); // [builder]
		a.ldc(userAgentName.index()); // [builder, name]
		a.ldc(userAgentValue.index()); // [builder, name, value]
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(builderHeader.index());
		a.op(3); // count: this + 2 args
		a.op(0);
		a.op(Opcode.POP); // discard returned builder
		a.bind(uaDone);

		// builder.method(canonicalMethod, publisher)
		a.aload(2); // [builder]
		a.aload(16); // [builder, method]
		a.aload(18); // [builder, method, publisher]
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(builderMethod.index());
		a.op(3); // count: this + 2 args
		a.op(0);
		a.op(Opcode.POP); // discard returned builder

		// future = HttpClient.newHttpClient().sendAsync(builder.build(),
		// BodyHandlers.ofString()) -- the request starts NOW and runs on the client's
		// executor threads while the compiled program continues. The future itself is
		// the returned value.
		a.op(Opcode.INVOKESTATIC);
		a.u2(newHttpClient.index()); // [client]
		a.aload(2); // [client, builder]
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(builderBuild.index());
		a.op(1);
		a.op(0); // [client, request]
		a.op(Opcode.INVOKESTATIC);
		a.u2(ofString.index()); // [client, request, handler]
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(clientSendAsync.index()); // [future]
		a.areturn();

		List<Integer> code = a.finish();
		Utf8Constant nameUtf8 = cp.addUtf8(METHOD_NAME);
		Utf8Constant descUtf8 = cp.addUtf8(METHOD_DESC);
		FetchMethod fetch = new FetchMethod(nameUtf8, descUtf8, 12, 20, code);

		return new FetchRuntime(fetch);
	}

	/**
	 * Emits a property-list lookup. Walks {@code (k1 v1 k2 v2 ...)} held in local slot 1
	 * (the options argument), comparing each key (a keyword symbol = runtime String)
	 * against {@code key}; stores the matching value (or null) into {@code resultSlot},
	 * using {@code cursorSlot} as scratch.
	 */
	private static void emitPlistGet(Asm a, ConstantPool.StringConstant key, int cursorSlot, int resultSlot,
			ClassConstant objectArrayClass, ClassConstant stringClass, MethodrefConstant stringEquals) {
		a.aload(1);
		a.astore(cursorSlot); // cursor = options
		a.aconstNull();
		a.astore(resultSlot);
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.aload(cursorSlot);
		a.branch(Opcode.IFNULL, end);
		// key = car(cursor)
		a.aload(cursorSlot);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(stringClass);
		a.ldc(key.index());
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(stringEquals.index()); // [bool]
		int notMatch = a.label();
		a.branch(Opcode.IFEQ, notMatch);
		// value = car(cdr(cursor))
		a.aload(cursorSlot);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.astore(resultSlot);
		a.branch(Opcode.GOTO, end);
		a.bind(notMatch);
		// cursor = cdr(cdr(cursor))
		a.aload(cursorSlot);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(cursorSlot);
		a.branch(Opcode.GOTO, loop);
		a.bind(end);
	}

	/** Loads local {@code slot} (a quoted runtime String) and strips the quotes. */
	private static void stripQuotes(Asm a, int slot, ClassConstant stringClass, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring) {
		a.aload(slot);
		a.checkcast(stringClass);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring);
	}

	/** Strips the surrounding quotes off the String on top of the stack. */
	private static void stripQuotesValue(Asm a, ClassConstant stringClass, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring) {
		a.checkcast(stringClass); // [s]
		a.op(Opcode.DUP); // [s, s]
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(stringLength.index()); // [s, len]
		a.iconst(1);
		a.op(Opcode.ISUB); // [s, len-1]
		a.iconst(1);
		a.op(Opcode.SWAP); // [s, 1, len-1]
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(stringSubstring.index()); // [inner]
	}

	/** Minimal label-based assembler, mirroring the one in JvmEvalRuntimeBuilder. */
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

		void aastore() {
			this.code.add(Opcode.AASTORE);
		}

		void aconstNull() {
			this.code.add(Opcode.ACONST_NULL);
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

		void anewarray(ClassConstant c) {
			this.code.add(Opcode.ANEWARRAY);
			JvmRuntimeBuilder.emitU2(this.code, c.index());
		}

		void areturn() {
			this.code.add(Opcode.ARETURN);
		}

		List<Integer> finish() {
			if (!this.pending.isEmpty()) {
				throw new IllegalStateException("Unbound labels in http runtime assembly: " + this.pending.keySet());
			}
			return this.code;
		}

	}

}
