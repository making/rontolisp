package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's native acceleration of ironclad's {@code pbkdf2-derive-key}: the
 * PBKDF2 loop is replaced by {@link Sha2Kernels}, so a SCRAM-SHA-256 PostgreSQL connect
 * costs milliseconds instead of tens of seconds.
 *
 * <h2>Why the interpreter only</h2>
 *
 * PBKDF2 is 4096 rounds of HMAC-SHA-256, i.e. ~16000 SHA-256 compressions, and a
 * compression interpreted out of ironclad's Lisp source costs ~0.8 ms -- 17 s for one
 * {@code cl-postgres} handshake. The SAME work is ~1 s on the JVM backend and ~3 s on a
 * WASM component, which is why nothing here has a compile-path sibling: the compiled
 * backends run ironclad's own code and are fast enough. Measurements and their history:
 * {@code .kb/asdf.md} (the ironclad slice).
 *
 * <h2>Why this is not a behavior divergence</h2>
 *
 * PBKDF2-HMAC-SHA-224/256 is a spec-defined function of its arguments, so the kernel and
 * ironclad's Lisp code are interchangeable BY CONSTRUCTION -- unlike {@code --simd},
 * which trades float precision and is therefore opt-in, this needs no flag and is always
 * on. The interpreter leg of {@code IroncladE2eTest} (FIPS 180-2 / RFC 4231 / RFC 5869 /
 * RFC 7677 vectors) is what pins that equivalence, and its three compiled legs keep
 * exercising ironclad's Lisp inner loop, so nothing loses coverage.
 *
 * <h2>The declined-input protocol</h2>
 *
 * Exactly {@link LinalgSimd}'s: the native captures the {@code defun} it replaces and
 * returns to it for any input it does not handle -- a digest other than SHA-224/256, a
 * non-octet passphrase or salt, an out-of-range iteration count or key length. ironclad
 * therefore remains the single source of truth for every edge case, including the exact
 * {@code check-type} error text.
 */
final class IroncladNative {

	/**
	 * The global function name the acceleration replaces. Internal (not exported by
	 * ironclad's {@code defpackage}), hence the double colon; {@code CRYPTO} is a
	 * nickname of {@code IRONCLAD}, and the resolver stores the canonical name.
	 */
	static final String PBKDF2_DERIVE_KEY = "IRONCLAD::PBKDF2-DERIVE-KEY";

	private IroncladNative() {
	}

	/**
	 * Overrides {@code ironclad::pbkdf2-derive-key} in the given (global) environment
	 * with the native kernel. Must be called AFTER ironclad's {@code kdf/pkcs5.lisp} has
	 * been evaluated into the same environment -- the override captures the defun it
	 * replaces and falls back to it.
	 * @param globalEnv the global environment holding the loaded ironclad slice
	 * @param evaluator the evaluator used to apply the captured defun on fallback
	 */
	static void install(Environment globalEnv, LispEvaluator evaluator) {
		LispVal lispDefun = globalEnv.lookupFunctionOrNull(PBKDF2_DERIVE_KEY);
		if (lispDefun == null) {
			throw new IllegalStateException("ironclad must be loaded before " + PBKDF2_DERIVE_KEY + " can be replaced");
		}
		globalEnv.defineFunction(PBKDF2_DERIVE_KEY, new LispFunction(PBKDF2_DERIVE_KEY, args -> {
			LispVal fast = args.size() == 5 ? deriveKey(args) : null;
			return fast != null ? fast : evaluator.applyGlobal(lispDefun, args);
		}));
	}

	/**
	 * {@code (pbkdf2-derive-key digest passphrase salt iteration-count key-length)},
	 * natively. Answers {@code null} for anything the kernel does not cover.
	 */
	private static @Nullable LispVal deriveKey(List<LispVal> args) {
		Boolean sha224 = sha2Variant(args.get(0));
		byte[] passphrase = octets(args.get(1));
		byte[] salt = octets(args.get(2));
		if (sha224 == null || passphrase == null || salt == null) {
			return null;
		}
		if (!(args.get(3) instanceof LispInteger iterations) || !(args.get(4) instanceof LispInteger keyLength)) {
			return null;
		}
		// ironclad check-types both as (integer 1 *) and the key length also bounds the
		// allocation: leave both errors to the defun. The upper bounds are not taste --
		// a count past int range would TRUNCATE in the cast below and silently answer a
		// different key, so anything that does not fit declines.
		if (iterations.value() < 1 || iterations.value() > Integer.MAX_VALUE || keyLength.value() < 1
				|| keyLength.value() > Integer.MAX_VALUE) {
			return null;
		}
		byte[] derived = Sha2Kernels.pbkdf2(sha224, passphrase, salt, (int) iterations.value(),
				(int) keyLength.value());
		long[] elements = new long[derived.length];
		for (int i = 0; i < derived.length; i++) {
			elements[i] = derived[i] & 0xff;
		}
		return new LispIntVector(8, elements);
	}

	/**
	 * Recognizes a digest designator this kernel implements: {@code :sha256} /
	 * {@code :sha224} (what {@code cl-postgres} passes) or the same names as ironclad's
	 * own symbols. Answers {@code null} -- decline -- for every other digest, including a
	 * same-named symbol from an unrelated package, which ironclad itself rejects.
	 */
	private static @Nullable Boolean sha2Variant(LispVal designator) {
		if (!(designator instanceof LispSymbol symbol)) {
			return null;
		}
		if (!symbol.isKeyword()) {
			PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(symbol.name());
			if (qualified != null && !"IRONCLAD".equalsIgnoreCase(qualified.pkg())
					&& !"CRYPTO".equalsIgnoreCase(qualified.pkg())) {
				return null;
			}
		}
		return switch (LispSymbol.memberName(symbol.name()).toUpperCase(Locale.ROOT)) {
			case "SHA256" -> Boolean.FALSE;
			case "SHA224" -> Boolean.TRUE;
			default -> null;
		};
	}

	/**
	 * Reads an octet sequence: the packed {@code (unsigned-byte 8)} vector ironclad's
	 * {@code ascii-string-to-byte-array} and friends build, or a general rank-1 array
	 * whose elements all happen to be octets (what a library building its buffers the
	 * boxed way hands over). Anything else declines.
	 */
	private static byte @Nullable [] octets(LispVal value) {
		if (value instanceof LispIntVector packed && packed.width() == 8) {
			byte[] bytes = new byte[packed.length()];
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) packed.elementAt(i);
			}
			return bytes;
		}
		if (value instanceof LispArray array && array.dimensions().length == 1 && !array.hasFillPointer()
				&& array.displacedTo() == null) {
			byte[] bytes = new byte[array.totalSize()];
			for (int i = 0; i < bytes.length; i++) {
				if (!(array.readFlat(i) instanceof LispInteger element) || element.value() < 0
						|| element.value() > 255) {
					return null;
				}
				bytes[i] = (byte) element.value();
			}
			return bytes;
		}
		return null;
	}

}
