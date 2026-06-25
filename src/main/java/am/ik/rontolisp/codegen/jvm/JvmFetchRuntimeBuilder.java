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

/**
 * Builds the JVM bytecode for the {@code rontolisp:fetch} built-in, emitted as a
 * {@code private static Object _fetch(Object url, Object options)} method into the
 * generated standalone {@code .class}. The method performs an outgoing HTTP request
 * (JavaScript {@code fetch}-style) using the JDK {@link java.net.http.HttpClient} and
 * returns the property list {@code (:status <int> :body <string> :headers <alist>)} in
 * the shared runtime value representation ({@code Long} status, quote-wrapped
 * {@code String} body, and an alist of quote-wrapped {@code (name . value)} string pairs,
 * one entry per response header name).
 *
 * <p>
 * The optional {@code options} argument is a property list; {@code :method} (default
 * {@code "GET"}; one of GET/HEAD/POST/PUT/DELETE/OPTIONS/PATCH, matched
 * case-insensitively and sent in canonical upper case), {@code :headers} (a
 * request-header alist) and {@code :body} (a request body string) are recognized. The
 * method is only emitted when the program actually uses {@code rontolisp:fetch}.
 * {@code HttpClient.send} declares checked
 * {@code IOException}/{@code InterruptedException}, but the JVM does not enforce checked
 * exceptions at the bytecode level, so the call needs no exception table; an exception
 * simply propagates.
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
	 * Builds the {@code _fetch} method body and the constant-pool entries it references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param objectClass {@code java/lang/Object}
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringClass {@code java/lang/String}
	 * @param longValueOf {@code Long.valueOf(J)}
	 * @param stringLength {@code String.length()}
	 * @param stringSubstring {@code String.substring(II)}
	 * @param stringConcat {@code String.concat(String)}
	 * @return the method body
	 */
	static FetchMethod build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, MethodrefConstant longValueOf,
			MethodrefConstant stringLength, MethodrefConstant stringSubstring, MethodrefConstant stringConcat) {
		// --- Interface / class references for the JDK HTTP client ---
		ClassConstant uriClass = cp.addClass(cp.addUtf8("java/net/URI"));
		MethodrefConstant uriCreate = cp.addMethodref(uriClass,
				cp.addNameAndType(cp.addUtf8("create"), cp.addUtf8("(Ljava/lang/String;)Ljava/net/URI;")));

		ClassConstant httpClientClass = cp.addClass(cp.addUtf8("java/net/http/HttpClient"));
		MethodrefConstant newHttpClient = cp.addMethodref(httpClientClass,
				cp.addNameAndType(cp.addUtf8("newHttpClient"), cp.addUtf8("()Ljava/net/http/HttpClient;")));
		MethodrefConstant clientSend = cp
			.addMethodref(httpClientClass, cp.addNameAndType(cp.addUtf8("send"), cp.addUtf8(
					"(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;")));

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

		ClassConstant httpResponseClass = cp.addClass(cp.addUtf8("java/net/http/HttpResponse"));
		MethodrefConstant statusCode = cp.addInterfaceMethodref(httpResponseClass,
				cp.addNameAndType(cp.addUtf8("statusCode"), cp.addUtf8("()I")));
		MethodrefConstant responseBody = cp.addInterfaceMethodref(httpResponseClass,
				cp.addNameAndType(cp.addUtf8("body"), cp.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant responseHeaders = cp.addInterfaceMethodref(httpResponseClass,
				cp.addNameAndType(cp.addUtf8("headers"), cp.addUtf8("()Ljava/net/http/HttpHeaders;")));

		ClassConstant httpHeadersClass = cp.addClass(cp.addUtf8("java/net/http/HttpHeaders"));
		MethodrefConstant headersMap = cp.addMethodref(httpHeadersClass,
				cp.addNameAndType(cp.addUtf8("map"), cp.addUtf8("()Ljava/util/Map;")));

		ClassConstant mapClass = cp.addClass(cp.addUtf8("java/util/Map"));
		MethodrefConstant mapEntrySet = cp.addInterfaceMethodref(mapClass,
				cp.addNameAndType(cp.addUtf8("entrySet"), cp.addUtf8("()Ljava/util/Set;")));
		ClassConstant setClass = cp.addClass(cp.addUtf8("java/util/Set"));
		MethodrefConstant setIterator = cp.addInterfaceMethodref(setClass,
				cp.addNameAndType(cp.addUtf8("iterator"), cp.addUtf8("()Ljava/util/Iterator;")));
		ClassConstant iteratorClass = cp.addClass(cp.addUtf8("java/util/Iterator"));
		MethodrefConstant iteratorHasNext = cp.addInterfaceMethodref(iteratorClass,
				cp.addNameAndType(cp.addUtf8("hasNext"), cp.addUtf8("()Z")));
		MethodrefConstant iteratorNext = cp.addInterfaceMethodref(iteratorClass,
				cp.addNameAndType(cp.addUtf8("next"), cp.addUtf8("()Ljava/lang/Object;")));
		ClassConstant entryClass = cp.addClass(cp.addUtf8("java/util/Map$Entry"));
		MethodrefConstant entryGetKey = cp.addInterfaceMethodref(entryClass,
				cp.addNameAndType(cp.addUtf8("getKey"), cp.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant entryGetValue = cp.addInterfaceMethodref(entryClass,
				cp.addNameAndType(cp.addUtf8("getValue"), cp.addUtf8("()Ljava/lang/Object;")));

		ClassConstant iterableClass = cp.addClass(cp.addUtf8("java/lang/Iterable"));
		MethodrefConstant stringJoin = cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("join"),
				cp.addUtf8("(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;")));
		MethodrefConstant stringEquals = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant stringEqualsIgnoreCase = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equalsIgnoreCase"), cp.addUtf8("(Ljava/lang/String;)Z")));

		ClassConstant runtimeExceptionClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant runtimeExceptionInit = cp.addMethodref(runtimeExceptionClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));

		ConstantPool.StringConstant quote = cp.addString("\"");
		ConstantPool.StringConstant comma = cp.addString(", ");
		ConstantPool.StringConstant methodKey = cp.addString(":method");
		ConstantPool.StringConstant headersKey = cp.addString(":headers");
		ConstantPool.StringConstant statusKey = cp.addString(":status");
		ConstantPool.StringConstant bodyKey = cp.addString(":body");
		ConstantPool.StringConstant headersResultKey = cp.addString(":headers");
		ConstantPool.StringConstant unsupportedMsg = cp.addString("fetch: unsupported method");
		// The supported HTTP methods, in canonical (upper-case) form. The request is sent
		// with the canonical spelling regardless of the case the caller used.
		String[] methods = { "GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH" };
		ConstantPool.StringConstant[] methodConsts = new ConstantPool.StringConstant[methods.length];
		for (int i = 0; i < methods.length; i++) {
			methodConsts[i] = cp.addString(methods[i]);
		}

		// Local slots: 0 url, 1 options, 2 builder, 3 cursor, 4 response,
		// 5 response-header alist, 6 iterator, 7 entry, 8 pair, 9 request headers,
		// 10 method value, 11 plist cursor, 12 status (Long), 13 result accumulator,
		// 14 body (quoted String), 15 request-body value, 16 canonical method (String),
		// 17 method scratch (unquoted String), 18 body publisher.
		Asm a = new Asm();

		// --- options parsing: method (10), request headers (9), request body (15) ---
		emitPlistGet(a, methodKey, 11, 10, objectArrayClass, stringClass, stringEquals);
		emitPlistGet(a, headersKey, 11, 9, objectArrayClass, stringClass, stringEquals);
		emitPlistGet(a, bodyKey, 11, 15, objectArrayClass, stringClass, stringEquals);

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

		// builder.method(canonicalMethod, publisher)
		a.aload(2); // [builder]
		a.aload(16); // [builder, method]
		a.aload(18); // [builder, method, publisher]
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(builderMethod.index());
		a.op(3); // count: this + 2 args
		a.op(0);
		a.op(Opcode.POP); // discard returned builder

		// response = HttpClient.newHttpClient().send(builder.build(),
		// BodyHandlers.ofString())
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
		a.u2(clientSend.index()); // [response]
		a.astore(4);

		// status (slot 12) = Long.valueOf(response.statusCode())
		a.aload(4);
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(statusCode.index());
		a.op(1);
		a.op(0); // [statusI]
		a.op(Opcode.I2L);
		a.op(Opcode.INVOKESTATIC);
		a.u2(longValueOf.index());
		a.astore(12);

		// body (slot 14) = quote(response.body())
		a.aload(4);
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(responseBody.index());
		a.op(1);
		a.op(0);
		a.checkcast(stringClass);
		quoteWrap(a, quote, stringConcat);
		a.astore(14);

		// Build response-header alist into slot 5 (init null for definite assignment).
		a.aconstNull();
		a.astore(5);
		a.aload(4);
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(responseHeaders.index());
		a.op(1);
		a.op(0); // [HttpHeaders]
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(headersMap.index()); // [Map]
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(mapEntrySet.index());
		a.op(1);
		a.op(0); // [Set]
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(setIterator.index());
		a.op(1);
		a.op(0); // [Iterator]
		a.astore(6);
		int eLoop = a.label();
		int eEnd = a.label();
		a.bind(eLoop);
		a.aload(6);
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(iteratorHasNext.index());
		a.op(1);
		a.op(0); // [boolean]
		a.branch(Opcode.IFEQ, eEnd);
		// entry = iterator.next() (slot 7 = entry, slot 8 = pair array)
		a.aload(6);
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(iteratorNext.index());
		a.op(1);
		a.op(0);
		a.checkcast(entryClass);
		a.astore(7);
		// pair = new Object[2]
		a.iconst(2);
		a.anewarray(objectClass);
		a.astore(8);
		// pair[0] = "\"" + entry.getKey() + "\""
		a.aload(8);
		a.iconst(0);
		a.aload(7);
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(entryGetKey.index());
		a.op(1);
		a.op(0);
		a.checkcast(stringClass);
		quoteWrap(a, quote, stringConcat);
		a.aastore();
		// pair[1] = "\"" + String.join(", ", entry.getValue()) + "\""
		a.aload(8);
		a.iconst(1);
		a.ldc(comma.index());
		a.aload(7);
		a.op(Opcode.INVOKEINTERFACE);
		a.u2(entryGetValue.index());
		a.op(1);
		a.op(0);
		a.checkcast(iterableClass);
		a.op(Opcode.INVOKESTATIC);
		a.u2(stringJoin.index());
		quoteWrap(a, quote, stringConcat);
		a.aastore();
		// alist = new Object[]{ pair, alist }
		consSlots(a, objectClass, 8, 5);
		a.astore(5);
		a.branch(Opcode.GOTO, eLoop);
		a.bind(eEnd);

		// result = (:status status :body body :headers alist), built tail-first into slot
		// 13
		a.aconstNull();
		a.astore(13);
		consSlots(a, objectClass, 5, 13); // (alist)
		a.astore(13);
		consLdcCdr(a, objectClass, headersResultKey, 13); // (:headers alist)
		a.astore(13);
		consSlots(a, objectClass, 14, 13); // (body :headers alist)
		a.astore(13);
		consLdcCdr(a, objectClass, bodyKey, 13); // (:body body :headers alist)
		a.astore(13);
		consSlots(a, objectClass, 12, 13); // (status :body body :headers alist)
		a.astore(13);
		consLdcCdr(a, objectClass, statusKey, 13); // (:status status ...)
		a.areturn();

		List<Integer> code = a.finish();
		Utf8Constant nameUtf8 = cp.addUtf8(METHOD_NAME);
		Utf8Constant descUtf8 = cp.addUtf8(METHOD_DESC);
		// The header-pair construction nests Object[2] allocations a few deep; 12 leaves
		// headroom.
		return new FetchMethod(nameUtf8, descUtf8, 12, 20, code);
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

	/** Pushes {@code new Object[]{ aload(carSlot), aload(cdrSlot) }}. */
	private static void consSlots(Asm a, ClassConstant objectClass, int carSlot, int cdrSlot) {
		a.iconst(2);
		a.anewarray(objectClass);
		a.op(Opcode.DUP);
		a.iconst(0);
		a.aload(carSlot);
		a.aastore();
		a.op(Opcode.DUP);
		a.iconst(1);
		a.aload(cdrSlot);
		a.aastore();
	}

	/** Pushes {@code new Object[]{ <keyword symbol string>, aload(cdrSlot) }}. */
	private static void consLdcCdr(Asm a, ClassConstant objectClass, ConstantPool.StringConstant sym, int cdrSlot) {
		a.iconst(2);
		a.anewarray(objectClass);
		a.op(Opcode.DUP);
		a.iconst(0);
		a.ldc(sym.index());
		a.aastore();
		a.op(Opcode.DUP);
		a.iconst(1);
		a.aload(cdrSlot);
		a.aastore();
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
