package am.ik.rontolisp.macro;

/**
 * Who, besides program code holding a condition value, reads what a signal carries -- the
 * fact {@link LispMacroExpander#expandTopLevelDefinitions} sizes the condition-report
 * runtime by.
 */
public enum SignalMessages {

	/**
	 * Every signal's message is rendered: the interpreter and the JVM, and a wasm-GC
	 * module in exception-handling mode, whose handlers and entry landing pad read the
	 * payload. The report renderer is in whenever a condition can be BUILT, and every
	 * printing operator routes a condition through it.
	 */
	RENDERED,

	/**
	 * No signal's message is ever read: a wasm-GC module outside exception-handling mode,
	 * where an uncaught condition is a bare trap. The renderer's one consumer is a
	 * printing operator handed a condition value, so it is in only when program code can
	 * HOLD one.
	 */
	LAZY,

	/**
	 * The one reader outside program code is the entry function's uncaught report: a
	 * wasm-GC module with no catching form that {@code --report-locations} gave the
	 * report anyway. The renderer is in whenever a condition can be built -- the report
	 * renders every one that escapes -- but a printing operator routes through it only
	 * when program code can hold one, and the simple-condition report keeps no arm for a
	 * function control no site can supply.
	 */
	ENTRY_REPORT

}
