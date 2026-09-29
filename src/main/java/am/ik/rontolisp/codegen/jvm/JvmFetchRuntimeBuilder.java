package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import org.jspecify.annotations.Nullable;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.runtime.RontoFetch;

/**
 * Builds the JVM bytecode for the {@code rontolisp:fetch} built-in, emitted as a
 * {@code private static} method into the generated standalone {@code .class}.
 * {@code _fetch(Object url, Object options)} reads the options and hands the request to
 * {@link RontoFetch#start}, which <em>starts</em> it (JavaScript {@code fetch}-style) and
 * answers a future settling ONCE to the response plist -- so every await answers the same
 * plist -- or failing when the request cannot be built or sent: every failure but the
 * method surfaces at the await.
 *
 * <p>
 * The optional {@code options} argument is a property list; {@code :method} (default
 * {@code "GET"}; one of GET/HEAD/POST/PUT/DELETE/OPTIONS/PATCH, matched
 * case-insensitively and sent in canonical upper case -- anything else throws AT THE
 * CALL), {@code :headers} (a request-header alist) and {@code :body} (a request body
 * string) are recognized. Each value is rendered to its text here, through the program's
 * own {@code _strv} (a mutable character vector is this class's representation, not the
 * transport's). {@link FetchResponseShape#defaultUserAgent()} is baked in at codegen time
 * and set by the transport when the caller's fields name no user-agent. The transport
 * class travels with the compiled output ({@link #RUNTIME_CLASS_FILES}).
 */
final class JvmFetchRuntimeBuilder {

	/** The emitted {@code _fetch} method name. */
	static final String METHOD_NAME = "_fetch";

	/** The {@code _fetch} method descriptor. */
	static final String METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/** The transport class {@code _fetch} calls, in internal form. */
	static final String TRANSPORT_CLASS = "am/ik/rontolisp/runtime/RontoFetch";

	/** {@link RontoFetch#start}'s descriptor. */
	static final String TRANSPORT_START_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;"
			+ "Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";

	/** The runtime class files a program that fetches carries beside it. */
	static final List<String> RUNTIME_CLASS_FILES = List.of(TRANSPORT_CLASS + ".class");

	private JvmFetchRuntimeBuilder() {
	}

	/** A ready-to-emit method body. */
	record FetchMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
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
	 * @param strvRef the program's {@code _strv}, or null without the array runtime
	 * @return the method body
	 */
	static FetchRuntime build(ConstantPool cp, ClassConstant objectArrayClass, ClassConstant stringClass,
			MethodrefConstant stringLength, MethodrefConstant stringSubstring, @Nullable MethodrefConstant strvRef) {
		// The transport builds the response plist in its own key order; the shape every
		// backend derives from the http-plist WIT record must still be that order, or the
		// compile fails here rather than a key going missing at run time.
		List<String> keywords = FetchResponseShape.responseFields()
			.stream()
			.map(FetchResponseShape.Field::keyword)
			.toList();
		if (!keywords.equals(RontoFetch.RESPONSE_KEYWORDS)) {
			throw new IllegalStateException("The JVM fetch transport builds the response plist "
					+ RontoFetch.RESPONSE_KEYWORDS + ", but the response shape is " + keywords);
		}
		// --- the transport (runtime/RontoFetch, which travels with the class) ---
		ClassConstant fetchClass = cp.addClass(cp.addUtf8(TRANSPORT_CLASS));
		MethodrefConstant fetchStart = cp.addMethodref(fetchClass,
				cp.addNameAndType(cp.addUtf8("start"), cp.addUtf8(TRANSPORT_START_DESC)));
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		MethodrefConstant arrayListInit = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant arrayListAdd = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));

		MethodrefConstant stringEqualsIgnoreCase = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equalsIgnoreCase"), cp.addUtf8("(Ljava/lang/String;)Z")));

		ClassConstant runtimeExceptionClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant runtimeExceptionInit = cp.addMethodref(runtimeExceptionClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));

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

		// Local slots: 0 url, 1 options, 3 cursor, 9 request headers, 10 method value,
		// 11 plist cursor, 15 request-body value, 16 canonical method (String),
		// 17 method scratch (unquoted String), 18 body text (null = none),
		// 19 flattened request fields (ArrayList), 20 the current field pair.
		MethodCode a = new MethodCode();

		// --- options parsing: method (10), request headers (9), request body (15) ---
		// Keyword-argument matching is case-insensitive (the reader upcases source
		// keywords to :METHOD/:HEADERS/:BODY).
		emitPlistGet(a, methodKey, 11, 10, objectArrayClass, stringClass, stringEqualsIgnoreCase);
		emitPlistGet(a, headersKey, 11, 9, objectArrayClass, stringClass, stringEqualsIgnoreCase);
		emitPlistGet(a, bodyKey, 11, 15, objectArrayClass, stringClass, stringEqualsIgnoreCase);

		// --- resolve the canonical method into slot 16: nil defaults to GET, otherwise
		// it
		// must match one of the supported methods (case-insensitively). ---
		MethodCode.Label methodDone = a.newLabel();
		a.aload(10);
		MethodCode.Label methodGiven = a.newLabel();
		a.ifnonnull(methodGiven);
		a.ldc(methodConsts[0].entry()); // "GET"
		a.astore(16);
		a.goto_(methodDone);
		a.labelBinding(methodGiven);
		a.aload(10);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring, strvRef); // [methodStr]
		a.astore(17);
		for (ConstantPool.StringConstant m : methodConsts) {
			MethodCode.Label next = a.newLabel();
			a.aload(17);
			a.ldc(m.entry());
			a.invokevirtual(stringEqualsIgnoreCase.methodRefEntry()); // [bool]
			a.ifeq(next);
			a.ldc(m.entry());
			a.astore(16);
			a.goto_(methodDone);
			a.labelBinding(next);
		}
		// none matched: throw new RuntimeException("fetch: unsupported method")
		a.new_(runtimeExceptionClass.entry());
		a.dup();
		a.ldc(unsupportedMsg.entry());
		a.invokespecial(runtimeExceptionInit.entry());
		a.athrow();
		a.labelBinding(methodDone);

		// --- the request body into slot 18: nil stays null (no body), otherwise its
		// text.
		MethodCode.Label bodyDone = a.newLabel();
		a.aconst_null();
		a.astore(18);
		a.aload(15);
		a.ifnull(bodyDone);
		a.aload(15);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring, strvRef); // [bodyStr]
		a.astore(18);
		a.labelBinding(bodyDone);

		// --- the request-header alist (slot 9) flattened into name, value, ... (slot 19)
		a.new_(arrayListClass.entry());
		a.dup();
		a.invokespecial(arrayListInit.entry());
		a.astore(19);
		a.aload(9);
		a.astore(3); // cursor = request headers
		MethodCode.Label hLoop = a.newLabel();
		MethodCode.Label hEnd = a.newLabel();
		a.labelBinding(hLoop);
		a.aload(3);
		a.ifnull(hEnd); // while cursor != null
		// pair = ((Object[]) cursor)[0]
		a.aload(3);
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass.entry());
		a.astore(20);
		for (int part = 0; part < 2; part++) {
			a.aload(19);
			a.aload(20);
			a.loadConstant(part);
			a.aaload();
			stripQuotesValue(a, stringClass, stringLength, stringSubstring, strvRef); // [list,
																						// text]
			a.invokevirtual(arrayListAdd.methodRefEntry());
			a.pop();
		}
		// cursor = ((Object[]) cursor)[1]
		a.aload(3);
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(1);
		a.aaload();
		a.astore(3);
		a.goto_(hLoop);
		a.labelBinding(hEnd);

		// return RontoFetch.start(url, method, headers, body, defaultUserAgent): the
		// request starts NOW, and what comes back is the future of the response plist.
		stripQuotes(a, 0, stringClass, stringLength, stringSubstring, strvRef); // [url]
		a.aload(16);
		a.aload(19);
		a.aload(18);
		a.ldc(userAgentValue.entry());
		a.invokestatic(fetchStart.entry());
		a.areturn();

		Utf8Constant nameUtf8 = cp.addUtf8(METHOD_NAME);
		Utf8Constant descUtf8 = cp.addUtf8(METHOD_DESC);
		FetchMethod fetch = new FetchMethod(nameUtf8, descUtf8, a);

		return new FetchRuntime(fetch);
	}

	/**
	 * Emits a property-list lookup. Walks {@code (k1 v1 k2 v2 ...)} held in local slot 1
	 * (the options argument), comparing each key (a keyword symbol = runtime String)
	 * against {@code key}; stores the matching value (or null) into {@code resultSlot},
	 * using {@code cursorSlot} as scratch.
	 */
	private static void emitPlistGet(MethodCode a, ConstantPool.StringConstant key, int cursorSlot, int resultSlot,
			ClassConstant objectArrayClass, ClassConstant stringClass, MethodrefConstant stringEquals) {
		a.aload(1);
		a.astore(cursorSlot); // cursor = options
		a.aconst_null();
		a.astore(resultSlot);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label end = a.newLabel();
		a.labelBinding(loop);
		a.aload(cursorSlot);
		a.ifnull(end);
		// key = car(cursor)
		a.aload(cursorSlot);
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(0);
		a.aaload();
		a.checkcast(stringClass.entry());
		a.ldc(key.entry());
		a.invokevirtual(stringEquals.methodRefEntry()); // [bool]
		MethodCode.Label notMatch = a.newLabel();
		a.ifeq(notMatch);
		// value = car(cdr(cursor))
		a.aload(cursorSlot);
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(1);
		a.aaload();
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(0);
		a.aaload();
		a.astore(resultSlot);
		a.goto_(end);
		a.labelBinding(notMatch);
		// cursor = cdr(cdr(cursor))
		a.aload(cursorSlot);
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(1);
		a.aaload();
		a.checkcast(objectArrayClass.entry());
		a.loadConstant(1);
		a.aaload();
		a.astore(cursorSlot);
		a.goto_(loop);
		a.labelBinding(end);
	}

	/** Loads local {@code slot} (a quoted runtime String) and strips the quotes. */
	private static void stripQuotes(MethodCode a, int slot, ClassConstant stringClass, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring, @Nullable MethodrefConstant strvRef) {
		a.aload(slot);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring, strvRef);
	}

	/**
	 * Strips the surrounding quotes off the String on top of the stack. A mutable
	 * character vector renders to its quote-framed string first through {@code _strv} (a
	 * concatenate/subseq/format-built URL, method, header or body -- the relay-handler
	 * shape builds its upstream URL with {@code concatenate}); null without the array
	 * runtime, where no character vector can exist.
	 */
	private static void stripQuotesValue(MethodCode a, ClassConstant stringClass, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring, @Nullable MethodrefConstant strvRef) {
		if (strvRef != null) {
			a.invokestatic(strvRef.entry());
		}
		a.checkcast(stringClass.entry()); // [s]
		a.dup(); // [s, s]
		a.invokevirtual(stringLength.methodRefEntry()); // [s, len]
		a.loadConstant(1);
		a.isub(); // [s, len-1]
		a.loadConstant(1);
		a.swap(); // [s, 1, len-1]
		a.invokevirtual(stringSubstring.methodRefEntry()); // [inner]
	}

}
