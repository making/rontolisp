package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * The interpreter's slot on a {@link LispLambda} for what it made of the body: nothing
 * until the body has run often enough, then a compiled form of it the interpreter runs in
 * place of walking the conses ({@code .kb/interpreter-compiled-bodies.md}). Opaque to
 * everything but the interpreter, like {@link LispSymbol#evaluatorNote}. One slot is
 * shared by every closure one site makes, so their calls count together and one compile
 * serves them all.
 */
public final class BodyCode {

	private volatile @Nullable Object code;

	private int calls;

	/**
	 * The interpreter's compiled body: opaque to everything else.
	 * @return it, or null
	 */
	public @Nullable Object code() {
		return this.code;
	}

	/**
	 * Replaces the compiled body. Racing writers each publish an immutable object, so a
	 * reader sees one of them whole.
	 * @param code the compiled body
	 */
	public void code(@Nullable Object code) {
		this.code = code;
	}

	/**
	 * Counts one call and answers how many there were. Not synchronized: a count two
	 * threads lose only puts the compile off by a call.
	 * @return the calls so far, this one included
	 */
	public int countCall() {
		int counted = this.calls + 1;
		this.calls = counted;
		return counted;
	}

}
