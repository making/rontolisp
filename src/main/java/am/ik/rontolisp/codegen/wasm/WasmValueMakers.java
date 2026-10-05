package am.ik.rontolisp.codegen.wasm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which emitted function MAKES each function value: the funcIds Pass 2 materializes as a
 * closure ({@code Ctx.valueFuncIds}), each with the module function whose body was being
 * compiled when its {@code i32.const funcId; ...; struct.new $closure} was written.
 *
 * <p>
 * A funcId joins the dispatch ladders wherever its {@code (lambda ...)} / {@code #'name}
 * is compiled -- a body the tree shaker then drops included -- and the ladder's
 * {@code call} would keep the callee, and everything it reaches, for a value nothing can
 * make. This is the half of the answer only the compiler knows: the shake asks it
 * ({@code WasmTreeShaker.madeValues}) which values a KEPT function makes, and the ladders
 * are rebuilt over those ({@code .kb/optimize-dead-code-elimination.md}, "A ladder arm
 * lives while a kept function makes its value").
 *
 * <p>
 * The unit is the function a Pass 2 driver is compiling: a defun (Pass 2a),
 * {@code _start} (Pass 2b, its top-level chunks included -- each is called from
 * {@code _start} directly), a lambda (Pass 2c). A body compiled while one of those is
 * open -- an async lambda's resume halves, a top-level chunk -- is reached only through
 * code that unit emitted, so crediting the unit is never later than the truth. A value
 * made while no unit is open is {@link #UNATTRIBUTED}: made unconditionally.
 */
final class WasmValueMakers {

	/** The maker of a value no unit was open for: every module makes it. */
	static final int UNATTRIBUTED = -1;

	private int unit = UNATTRIBUTED;

	private final Map<Integer, Set<Integer>> makers = new HashMap<>();

	/**
	 * Opens the unit every value noted until {@link #close} is credited to.
	 * @param funcIndex the module index (before host-import injection) of the function
	 * being compiled
	 */
	void open(int funcIndex) {
		this.unit = funcIndex;
	}

	/** Closes the open unit: a value noted now is {@link #UNATTRIBUTED}. */
	void close() {
		this.unit = UNATTRIBUTED;
	}

	/**
	 * Records that the open unit makes {@code funcId}'s value.
	 * @param funcId the funcId just materialized as a closure
	 */
	void note(int funcId) {
		this.makers.computeIfAbsent(funcId, k -> new HashSet<>()).add(this.unit);
	}

	/**
	 * Every value and the functions that make it.
	 * @return funcId to maker function indices ({@link #UNATTRIBUTED} among them for a
	 * value made outside any unit)
	 */
	Map<Integer, Set<Integer>> makers() {
		return this.makers;
	}

}
