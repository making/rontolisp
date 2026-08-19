package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The call-site shape of the {@code linalg:} kernels that take OPTIONS beyond their
 * required arguments, shared by the JVM and wasm {@code --simd} interceptors so both
 * pattern-match the same spellings.
 *
 * <p>
 * Options in {@code linalg.lisp} are numpy-style keyword arguments: {@code (linalg:sum a
 * :axis 0 :keepdims t)}, {@code (linalg:argmax v :axis 1)}. The one positional option is
 * {@code transpose}'s axes permutation ({@code (linalg:transpose a '(0 3 1 2))}, numpy's
 * {@code a.transpose(0, 3, 1, 2)}). The kernels themselves stay positional ({@code
 * laSumAxis(a, axis, keepdims)}), so an interceptor needs, for each kernel parameter,
 * WHICH argument form of the call supplies it -- that is {@link #layout}. A call whose
 * keywords are not literal symbols (or are unknown, repeated, or odd) is not a kernel
 * call at all: it compiles as an ordinary direct call of the spliced defun, whose
 * {@code &key} prologue then signals the same error it always did.
 */
public final class LinalgKernelCallLayout {

	private LinalgKernelCallLayout() {
	}

	/**
	 * The extended shape of one member: the kernel's parameter count (the required
	 * arguments first) and, for a keyword member, the keyword names in kernel-parameter
	 * order. An empty keyword list means the surplus arguments are positional
	 * ({@code transpose}'s axes).
	 *
	 * @param params the kernel's total parameter count
	 * @param keywords the keyword names (without the colon) in kernel order, or empty
	 */
	public record Extended(int params, List<String> keywords) {
	}

	private static final Map<String, Extended> EXTENDED = Map.of(LispNames.LINALG_TRANSPOSE, new Extended(2, List.of()),
			LispNames.LINALG_SUM, new Extended(3, List.of("AXIS", "KEEPDIMS")), LispNames.LINALG_AMAX,
			new Extended(3, List.of("AXIS", "KEEPDIMS")), LispNames.LINALG_AMIN,
			new Extended(3, List.of("AXIS", "KEEPDIMS")), LispNames.LINALG_ARGMAX, new Extended(2, List.of("AXIS")),
			LispNames.LINALG_ARGMIN, new Extended(2, List.of("AXIS")));

	/**
	 * Returns the extended shape of the given member, or {@code null} when the member has
	 * only its required arguments.
	 * @param member the unqualified member name
	 * @return the extended shape, or {@code null}
	 */
	public static @Nullable Extended extended(String member) {
		return EXTENDED.get(member);
	}

	/**
	 * Maps a call's argument forms onto the kernel's parameters. Returns an array of
	 * {@code ext.params()} indexes into {@code argForms} ({@code -1} = not supplied, the
	 * kernel receives nil), or {@code null} when the call does not have the extended
	 * shape: fewer forms than the required count, a positional member with too many, or a
	 * keyword member whose tail is not literal {@code :keyword value} pairs over the
	 * declared keywords, each at most once.
	 * @param ext the member's extended shape
	 * @param arity the member's required argument count
	 * @param argForms the call's argument forms (the operator excluded)
	 * @return the per-parameter form indexes, or {@code null}
	 */
	public static int @Nullable [] layout(Extended ext, int arity, List<LispVal> argForms) {
		int supplied = argForms.size();
		if (supplied <= arity) {
			return null;
		}
		int[] idx = new int[ext.params()];
		for (int i = 0; i < idx.length; i++) {
			idx[i] = i < arity ? i : -1;
		}
		if (ext.keywords().isEmpty()) {
			if (supplied > ext.params()) {
				return null;
			}
			for (int i = arity; i < supplied; i++) {
				idx[i] = i;
			}
			return idx;
		}
		if ((supplied - arity) % 2 != 0) {
			return null;
		}
		for (int i = arity; i < supplied; i += 2) {
			if (!(argForms.get(i) instanceof LispSymbol sym) || !sym.isKeyword()) {
				return null;
			}
			int k = ext.keywords().indexOf(sym.name().substring(1));
			if (k < 0 || idx[arity + k] != -1) {
				return null;
			}
			idx[arity + k] = i + 1;
		}
		return idx;
	}

}
