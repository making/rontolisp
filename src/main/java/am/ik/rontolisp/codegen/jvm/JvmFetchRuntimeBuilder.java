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
import org.jspecify.annotations.Nullable;
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
		stripQuotesValue(a, stringClass, stringLength, stringSubstring, strvRef); // [methodStr]
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

		// --- the request body into slot 18: nil stays null (no body), otherwise its
		// text.
		int bodyDone = a.label();
		a.aconstNull();
		a.astore(18);
		a.aload(15);
		a.branch(Opcode.IFNULL, bodyDone);
		a.aload(15);
		stripQuotesValue(a, stringClass, stringLength, stringSubstring, strvRef); // [bodyStr]
		a.astore(18);
		a.bind(bodyDone);

		// --- the request-header alist (slot 9) flattened into name, value, ... (slot 19)
		a.op(Opcode.NEW);
		a.u2(arrayListClass.index());
		a.op(Opcode.DUP);
		a.op(Opcode.INVOKESPECIAL);
		a.u2(arrayListInit.index());
		a.astore(19);
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
		a.aaload();
		a.checkcast(objectArrayClass);
		a.astore(20);
		for (int part = 0; part < 2; part++) {
			a.aload(19);
			a.aload(20);
			a.iconst(part);
			a.aaload();
			stripQuotesValue(a, stringClass, stringLength, stringSubstring, strvRef); // [list,
																						// text]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(arrayListAdd.index());
			a.op(Opcode.POP);
		}
		// cursor = ((Object[]) cursor)[1]
		a.aload(3);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(3);
		a.branch(Opcode.GOTO, hLoop);
		a.bind(hEnd);

		// return RontoFetch.start(url, method, headers, body, defaultUserAgent): the
		// request starts NOW, and what comes back is the future of the response plist.
		stripQuotes(a, 0, stringClass, stringLength, stringSubstring, strvRef); // [url]
		a.aload(16);
		a.aload(19);
		a.aload(18);
		a.ldc(userAgentValue.index());
		a.op(Opcode.INVOKESTATIC);
		a.u2(fetchStart.index());
		a.areturn();

		List<Integer> code = a.finish();
		Utf8Constant nameUtf8 = cp.addUtf8(METHOD_NAME);
		Utf8Constant descUtf8 = cp.addUtf8(METHOD_DESC);
		FetchMethod fetch = new FetchMethod(nameUtf8, descUtf8, 12, 21, code);

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
	private static void stripQuotesValue(Asm a, ClassConstant stringClass, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring, @Nullable MethodrefConstant strvRef) {
		if (strvRef != null) {
			a.op(Opcode.INVOKESTATIC);
			a.u2(strvRef.index());
		}
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
