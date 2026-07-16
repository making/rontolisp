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

/**
 * Builds the JVM-backend runtime for the {@code rontolisp:http-handler} directive. The
 * generated program class itself implements
 * {@code am.ik.rontolisp.eval.HttpHandlerSupport.Handler} (the same mechanism as the
 * tls-connect trust-all {@code X509TrustManager}: the backend cannot emit an anonymous
 * class, so the program class takes on the interface), the directive stores the compiled
 * handler funcref in the {@code _httpHandlerFn} static field and calls
 * {@code HttpHandlerSupport.serve(port, new Prog())}, and the injected public
 * {@code handle(Request)} method adapts each incoming request: it builds the request
 * property list {@code (:method m :path p :query q :headers <alist> :body b)} in the
 * shared runtime value representation (quote-wrapped strings, cons cells as
 * {@code Object[2]}), applies the handler through the {@code _invoke_1} dispatcher, and
 * reads {@code :status} (default 200), {@code :headers} (an alist of
 * {@code (name . value)} string pairs; malformed entries are skipped like the
 * interpreter's) and {@code :body} (default empty) back from the response property list.
 */
final class JvmHttpHandlerRuntimeBuilder {

	/** The internal name of the interpreter-shared HTTP server support class. */
	private static final String SUPPORT_CLASS = "am/ik/rontolisp/eval/HttpHandlerSupport";

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
	 * @param objectClass {@code java/lang/Object}
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringClass {@code java/lang/String}
	 * @param longClass {@code java/lang/Long}
	 * @param longValue {@code Long.longValue()}
	 * @param stringLength {@code String.length()}
	 * @param stringSubstring {@code String.substring(II)}
	 * @param stringConcat {@code String.concat(String)}
	 * @return the runtime refs and method body
	 */
	static HttpHandlerRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat) {
		ClassConstant handlerInterface = cp.addClass(cp.addUtf8(SUPPORT_CLASS + "$Handler"));
		ClassConstant requestClass = cp.addClass(cp.addUtf8(SUPPORT_CLASS + "$Request"));
		ClassConstant responseClass = cp.addClass(cp.addUtf8(SUPPORT_CLASS + "$Response"));
		ClassConstant supportClass = cp.addClass(cp.addUtf8(SUPPORT_CLASS));
		MethodrefConstant serve = cp.addMethodref(supportClass,
				cp.addNameAndType(cp.addUtf8("serve"), cp.addUtf8("(IL" + SUPPORT_CLASS + "$Handler;)V")));

		// The async runtime helpers (forced on whenever http-handler is used): the
		// request body streams in, the handler's future is awaited, a stream response
		// body drains out.
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

		Utf8Constant stringGetterDesc = cp.addUtf8("()Ljava/lang/String;");
		MethodrefConstant requestMethod = cp.addMethodref(requestClass,
				cp.addNameAndType(cp.addUtf8("method"), stringGetterDesc));
		MethodrefConstant requestPath = cp.addMethodref(requestClass,
				cp.addNameAndType(cp.addUtf8("path"), stringGetterDesc));
		MethodrefConstant requestQuery = cp.addMethodref(requestClass,
				cp.addNameAndType(cp.addUtf8("query"), stringGetterDesc));
		MethodrefConstant requestBody = cp.addMethodref(requestClass,
				cp.addNameAndType(cp.addUtf8("body"), stringGetterDesc));
		MethodrefConstant requestHeaders = cp.addMethodref(requestClass,
				cp.addNameAndType(cp.addUtf8("headers"), cp.addUtf8("()Ljava/util/List;")));
		MethodrefConstant responseInit = cp.addMethodref(responseClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(ILjava/util/List;Ljava/lang/String;)V")));
		ClassConstant headerClass = cp.addClass(cp.addUtf8(SUPPORT_CLASS + "$Header"));
		MethodrefConstant headerInit = cp.addMethodref(headerClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;Ljava/lang/String;)V")));
		MethodrefConstant headerName = cp.addMethodref(headerClass,
				cp.addNameAndType(cp.addUtf8("name"), stringGetterDesc));
		MethodrefConstant headerValue = cp.addMethodref(headerClass,
				cp.addNameAndType(cp.addUtf8("value"), stringGetterDesc));
		ClassConstant listClass = cp.addClass(cp.addUtf8("java/util/List"));
		MethodrefConstant listSize = cp.addInterfaceMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		MethodrefConstant listGet = cp.addInterfaceMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		MethodrefConstant arrayListInit = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant arrayListAdd = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));

		Utf8Constant handlerFieldName = cp.addUtf8("_httpHandlerFn");
		Utf8Constant handlerFieldDesc = cp.addUtf8("Ljava/lang/Object;");
		FieldrefConstant handlerField = cp.addFieldref(thisClass,
				cp.addNameAndType(handlerFieldName, handlerFieldDesc));
		MethodrefConstant progInit = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant invoke1 = cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("_invoke_1"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));

		ConstantPool.StringConstant quote = cp.addString("\"");
		ConstantPool.StringConstant emptyStr = cp.addString("");
		ConstantPool.StringConstant methodKey = cp.addString(":method");
		ConstantPool.StringConstant pathKey = cp.addString(":path");
		ConstantPool.StringConstant queryKey = cp.addString(":query");
		ConstantPool.StringConstant headersKey = cp.addString(":headers");
		ConstantPool.StringConstant bodyKey = cp.addString(":body");
		ConstantPool.StringConstant statusKey = cp.addString(":status");
		MethodrefConstant stringEquals = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));

		// handle(Request): slots 0 this, 1 request, 2 method, 3 path, 4 body (all
		// quote-wrapped), 5 request plist / response cursor base, 6 handler result,
		// 7 plist-get cursor, 8 plist-get value, 9 status (int), 10 response body,
		// 11 request-header List, 12 header loop index (int), 13 Header / pair scratch,
		// 14 request-header alist, 15 response-header ArrayList, 16 response alist
		// cursor, 17 query (quote-wrapped, or null = nil when the request has none).
		Asm a = new Asm();
		a.aload(1);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(requestMethod.index());
		quoteWrap(a, quote, stringConcat);
		a.astore(2);
		a.aload(1);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(requestPath.index());
		quoteWrap(a, quote, stringConcat);
		a.astore(3);
		a.aload(1);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(requestBody.index());
		quoteWrap(a, quote, stringConcat);
		a.astore(4);
		// The handler sees the request body as an asynchronous stream: one settled
		// quoted chunk when the request carries a body, an already-closed empty
		// stream otherwise (its first read observes end of stream) -- interpreter
		// parity.
		a.op(Opcode.INVOKESTATIC);
		a.u2(makeStream.index());
		a.astore(18);
		int bodyEmpty = a.label();
		a.aload(1);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(requestBody.index());
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(stringLength.index());
		a.branch(Opcode.IFEQ, bodyEmpty);
		a.aload(18);
		a.aload(4);
		a.op(Opcode.INVOKESTATIC);
		a.u2(streamWrite.index());
		a.op(Opcode.POP);
		a.bind(bodyEmpty);
		a.aload(18);
		a.op(Opcode.INVOKESTATIC);
		a.u2(streamClose.index());
		a.op(Opcode.POP);
		a.aload(18);
		a.astore(4);
		// query = request.query() quote-wrapped, or null (Lisp nil) when absent.
		a.aload(1);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(requestQuery.index());
		a.astore(17);
		int queryDone = a.label();
		a.aload(17);
		a.branch(Opcode.IFNULL, queryDone);
		a.aload(17);
		quoteWrap(a, quote, stringConcat);
		a.astore(17);
		a.bind(queryDone);

		// alist = request.headers() as ((name . value) ...), built back-to-front in
		// slot 14 so the plist below sees it in request order.
		a.aload(1);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(requestHeaders.index());
		a.astore(11);
		a.aconstNull();
		a.astore(14);
		a.aload(11);
		a.invokeInterface(listSize, 1);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(12);
		int hLoop = a.label();
		int hEnd = a.label();
		a.bind(hLoop);
		a.iload(12);
		a.branch(Opcode.IFLT, hEnd);
		// h = (Header) headers.get(i)
		a.aload(11);
		a.iload(12);
		a.invokeInterface(listGet, 2);
		a.checkcast(headerClass);
		a.astore(13);
		// pair = new Object[]{ quoted(h.name()), quoted(h.value()) }
		a.iconst(2);
		a.anewarray(objectClass);
		a.op(Opcode.DUP);
		a.iconst(0);
		a.aload(13);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(headerName.index());
		quoteWrap(a, quote, stringConcat);
		a.aastore();
		a.op(Opcode.DUP);
		a.iconst(1);
		a.aload(13);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(headerValue.index());
		quoteWrap(a, quote, stringConcat);
		a.aastore();
		a.astore(13);
		consSlots(a, objectClass, 13, 14); // (pair . alist)
		a.astore(14);
		a.iload(12);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(12);
		a.branch(Opcode.GOTO, hLoop);
		a.bind(hEnd);

		// plist = (:method m :path p :query q :headers alist :body b), built tail-first
		// in slot 5.
		a.aconstNull();
		a.astore(5);
		consSlots(a, objectClass, 4, 5); // (b)
		a.astore(5);
		consLdcCdr(a, objectClass, bodyKey, 5); // (:body b)
		a.astore(5);
		consSlots(a, objectClass, 14, 5); // (alist :body b)
		a.astore(5);
		consLdcCdr(a, objectClass, headersKey, 5); // (:headers nil ...)
		a.astore(5);
		consSlots(a, objectClass, 17, 5); // (q :headers ...)
		a.astore(5);
		consLdcCdr(a, objectClass, queryKey, 5); // (:query q ...)
		a.astore(5);
		consSlots(a, objectClass, 3, 5); // (p :query ...)
		a.astore(5);
		consLdcCdr(a, objectClass, pathKey, 5); // (:path p ...)
		a.astore(5);
		consSlots(a, objectClass, 2, 5); // (m :path ...)
		a.astore(5);
		consLdcCdr(a, objectClass, methodKey, 5); // (:method m ...)
		a.astore(5);

		// result = _await(_invoke_1(_httpHandlerFn, plist)) -- an async-defun handler
		// returns a future; each request runs on its own virtual thread, so awaiting
		// it here is the natural per-request suspension.
		a.op(Opcode.GETSTATIC);
		a.u2(handlerField.index());
		a.aload(5);
		a.op(Opcode.INVOKESTATIC);
		a.u2(invoke1.index());
		a.op(Opcode.INVOKESTATIC);
		a.u2(awaitHelper.index());
		a.astore(6);

		// status = (:status is a Long) ? (int) it : 200
		emitPlistGet(a, statusKey, 6, 7, 8, objectArrayClass, stringEquals);
		a.iconst(200);
		a.istore(9);
		int statusDone = a.label();
		a.aload(8);
		a.op(Opcode.INSTANCEOF);
		a.u2(longClass.index());
		a.branch(Opcode.IFEQ, statusDone);
		a.aload(8);
		a.checkcast(longClass);
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(longValue.index());
		a.op(Opcode.L2I);
		a.istore(9);
		a.bind(statusDone);

		// body = (:body is a quoted String) ? stripQuotes(it) : "" -- a stream body
		// drains to its quoted concatenation first (buffered send)
		emitPlistGet(a, bodyKey, 6, 7, 8, objectArrayClass, stringEquals);
		a.aload(8);
		a.op(Opcode.INVOKESTATIC);
		a.u2(drainBody.index());
		a.astore(8);
		a.ldc(emptyStr.index());
		a.astore(10);
		int bodyDone = a.label();
		a.aload(8);
		a.op(Opcode.INSTANCEOF);
		a.u2(stringClass.index());
		a.branch(Opcode.IFEQ, bodyDone);
		a.aload(8);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring);
		a.astore(10);
		a.bind(bodyDone);

		// hdrs = new ArrayList(); walk the :headers alist, adding each well-formed
		// (name . value) string pair and skipping anything else (the interpreter's
		// leniency).
		a.op(Opcode.NEW);
		a.u2(arrayListClass.index());
		a.op(Opcode.DUP);
		a.op(Opcode.INVOKESPECIAL);
		a.u2(arrayListInit.index());
		a.astore(15);
		emitPlistGet(a, headersKey, 6, 7, 8, objectArrayClass, stringEquals);
		a.aload(8);
		a.astore(16);
		int rLoop = a.label();
		int rEnd = a.label();
		int rSkip = a.label();
		a.bind(rLoop);
		a.aload(16);
		a.op(Opcode.INSTANCEOF);
		a.u2(objectArrayClass.index());
		a.branch(Opcode.IFEQ, rEnd);
		// pair = car(cursor); cursor = cdr(cursor)
		a.aload(16);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.astore(13);
		a.aload(16);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(16);
		// pair must be a cons whose car and cdr are (quoted) strings
		a.aload(13);
		a.op(Opcode.INSTANCEOF);
		a.u2(objectArrayClass.index());
		a.branch(Opcode.IFEQ, rSkip);
		a.aload(13);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.op(Opcode.INSTANCEOF);
		a.u2(stringClass.index());
		a.branch(Opcode.IFEQ, rSkip);
		a.aload(13);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.op(Opcode.INSTANCEOF);
		a.u2(stringClass.index());
		a.branch(Opcode.IFEQ, rSkip);
		// hdrs.add(new Header(strip(car(pair)), strip(cdr(pair))))
		a.aload(15);
		a.op(Opcode.NEW);
		a.u2(headerClass.index());
		a.op(Opcode.DUP);
		a.aload(13);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		stripQuotesValue(a, stringClass, stringLength, stringSubstring);
		a.aload(13);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		stripQuotesValue(a, stringClass, stringLength, stringSubstring);
		a.op(Opcode.INVOKESPECIAL);
		a.u2(headerInit.index());
		a.op(Opcode.INVOKEVIRTUAL);
		a.u2(arrayListAdd.index());
		a.op(Opcode.POP);
		a.bind(rSkip);
		a.branch(Opcode.GOTO, rLoop);
		a.bind(rEnd);

		// return new Response(status, hdrs, body)
		a.op(Opcode.NEW);
		a.u2(responseClass.index());
		a.op(Opcode.DUP);
		a.iload(9);
		a.aload(15);
		a.aload(10);
		a.op(Opcode.INVOKESPECIAL);
		a.u2(responseInit.index());
		a.areturn();

		HandleMethod handle = new HandleMethod(cp.addUtf8("handle"),
				cp.addUtf8("(L" + SUPPORT_CLASS + "$Request;)L" + SUPPORT_CLASS + "$Response;"), 8, 19, a.finish());
		return new HttpHandlerRuntime(handlerInterface, handlerFieldName, handlerFieldDesc, handlerField, serve,
				thisClass, progInit, handle);
	}

	/**
	 * Emits a property-list lookup over the list held in {@code srcSlot}, comparing each
	 * key against the string constant {@code key} (called on the constant, so a
	 * non-string key never throws); stores the matching value (or null) into
	 * {@code resultSlot}, using {@code cursorSlot} as scratch.
	 */
	private static void emitPlistGet(Asm a, ConstantPool.StringConstant key, int srcSlot, int cursorSlot,
			int resultSlot, ClassConstant objectArrayClass, MethodrefConstant stringEquals) {
		a.aload(srcSlot);
		a.astore(cursorSlot);
		a.aconstNull();
		a.astore(resultSlot);
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.aload(cursorSlot);
		a.branch(Opcode.IFNULL, end);
		// key.equals(car(cursor))
		a.ldc(key.index());
		a.aload(cursorSlot);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
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

		void iload(int slot) {
			this.code.add(Opcode.ILOAD);
			this.code.add(slot);
		}

		void istore(int slot) {
			this.code.add(Opcode.ISTORE);
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

		// count = argument slot count including the receiver (the JVM recomputes it,
		// but the bytes must be present).
		void invokeInterface(MethodrefConstant m, int count) {
			this.code.add(Opcode.INVOKEINTERFACE);
			JvmRuntimeBuilder.emitU2(this.code, m.index());
			this.code.add(count);
			this.code.add(0);
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
				throw new IllegalStateException(
						"Unbound labels in http-handler runtime assembly: " + this.pending.keySet());
			}
			return this.code;
		}

	}

}
