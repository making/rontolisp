package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's kernels of the Java subset's digests: the two functions
 * {@code java.lisp} answers "none" with on every other target are put in their place once
 * that library is loaded ({@code LispEvaluator#resolveFunction}), so a digest's blocks
 * and a PBKDF2 derivation run in {@link DigestKernels} where the library's Java source,
 * interpreted, would take seconds for one SCRAM login ({@code .kb/java-frontend.md},
 * "java.security and javax.crypto").
 *
 * <p>
 * Not a divergence: each answers a function of its arguments the standards define, and
 * the library's Java source -- what every compiled backend runs -- computes the same; the
 * corpus holds both to a JDK's answers. An argument of a shape the kernel does not take
 * answers nil, and the Java does the work.
 */
final class JavaDigestNative {

	/** A digest's blocks into its state: {@code (digest state data offset blocks)}. */
	static final String BLOCKS = "RONTOLISP::%JAVA-DIGEST-BLOCKS";

	/** PBKDF2: {@code (digest password salt iterations length)}. */
	static final String DERIVE = "RONTOLISP::%JAVA-DIGEST-DERIVE";

	private JavaDigestNative() {
	}

	/**
	 * Puts the kernels in the place of {@code java.lisp}'s two functions.
	 * @param globalEnv the global environment {@code java.lisp} was loaded into
	 */
	static void install(Environment globalEnv) {
		globalEnv.defineFunction(BLOCKS, new LispFunction(BLOCKS, JavaDigestNative::blocks));
		globalEnv.defineFunction(DERIVE, new LispFunction(DERIVE, JavaDigestNative::derive));
	}

	private static LispVal blocks(List<LispVal> args) {
		if (args.size() != 5) {
			return LispNil.INSTANCE;
		}
		int digest = number(args.get(0));
		byte[] data = octets(args.get(2));
		int offset = number(args.get(3));
		int blocks = number(args.get(4));
		if (digest < 0 || digest > DigestKernels.LAST || data == null || offset < 0 || blocks < 0
				|| (long) offset + (long) blocks * DigestKernels.block(digest) > data.length) {
			return LispNil.INSTANCE;
		}
		LispVal state = args.get(1);
		int words = DigestKernels.stateLength(digest);
		if (!DigestKernels.wide(digest) && state instanceof LispIntVector vector && vector.width() == 32
				&& vector.length() == words) {
			DigestKernels.compress(digest, vector.ints(), data, offset, blocks);
			return LispTrue.INSTANCE;
		}
		if (DigestKernels.wide(digest) && state instanceof LispArray array && array.dimensions().length == 1
				&& array.totalSize() == words) {
			long[] longs = new long[words];
			for (int i = 0; i < words; i++) {
				if (!(array.readFlat(i) instanceof LispInteger word)) {
					return LispNil.INSTANCE;
				}
				longs[i] = word.value();
			}
			DigestKernels.compress(digest, longs, data, offset, blocks);
			for (int i = 0; i < words; i++) {
				array.writeFlat(i, new LispInteger(longs[i]));
			}
			return LispTrue.INSTANCE;
		}
		return LispNil.INSTANCE;
	}

	private static LispVal derive(List<LispVal> args) {
		if (args.size() != 5) {
			return LispNil.INSTANCE;
		}
		int digest = number(args.get(0));
		byte[] password = octets(args.get(1));
		byte[] salt = octets(args.get(2));
		int iterations = number(args.get(3));
		int length = number(args.get(4));
		if (digest < 0 || digest > DigestKernels.LAST || password == null || salt == null || iterations < 1
				|| length < 0) {
			return LispNil.INSTANCE;
		}
		return LispIntVector.wrapOctets(DigestKernels.pbkdf2(digest, password, salt, iterations, length));
	}

	/** An integer argument within an {@code int}, or -1. */
	private static int number(LispVal value) {
		return value instanceof LispInteger integer && integer.value() >= 0 && integer.value() <= Integer.MAX_VALUE
				? (int) integer.value() : -1;
	}

	/** The live octets of a Java {@code byte[]}, or null for any other value. */
	private static byte @Nullable [] octets(LispVal value) {
		return value instanceof LispIntVector vector && vector.width() == 8 ? vector.octets() : null;
	}

}
