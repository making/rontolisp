package am.ik.rontolisp.cli;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.eval.CommonLispSwank;

/**
 * The fork of SWANK's protocol a request speaks, told by the package prefix of the
 * function it calls: SLIME's {@code swank}, SLY's {@code slynk} ({@code slynk-mrepl},
 * {@code slynk-completion} ...), Lem's {@code micros} ({@code micros/repl},
 * {@code micros/lsp-api} ...). The framing and {@code :emacs-rex} are the same; a fork
 * names its functions in its own packages, reports its own protocol version and module
 * names, names its client in its restarts and its interrupts, and answers a few requests
 * in shapes of its own.
 */
enum SwankDialect {

	/** SLIME's SWANK, measured against SLIME 2.32. */
	SWANK("swank", "2.32", List.of("SWANK-REPL", "SWANK-ARGLISTS", "SWANK-C-P-C", "SWANK-FUZZY", "SWANK-UTIL"),
			CommonLispSwank.Echo.SWANK, "SLIME", "sldb", "worker", CommonLispSwank.INTERRUPT_MESSAGE),

	/** SLY's Slynk, measured against SLY 1.0.43. */
	SLYNK("slynk", "1.0.43", List.of("SLYNK/COMPLETION"), CommonLispSwank.Echo.SLYNK, "SLY", "sly-db", "slynk-worker",
			"Interrupt from client"),

	/** Lem's micros, measured against Lem 2.3.0 with micros 2ec61c8. */
	MICROS("micros", "0.0.0", List.of(), CommonLispSwank.Echo.MICROS, "SLIME", "sldb", "worker",
			"Interrupt from Editor");

	private final String prefix;

	private final String protocolVersion;

	private final List<String> modules;

	private final CommonLispSwank.Echo echo;

	/** The client its restarts name: {@code Return to SLY's top level.} */
	private final String client;

	/** Its debugger's name, in a level's restart: {@code Return to sly-db level 1.} */
	private final String debugger;

	private final String workerName;

	private final String interruptMessage;

	SwankDialect(String prefix, String protocolVersion, List<String> modules, CommonLispSwank.Echo echo, String client,
			String debugger, String workerName, String interruptMessage) {
		this.prefix = prefix;
		this.protocolVersion = protocolVersion;
		this.modules = modules;
		this.echo = echo;
		this.client = client;
		this.debugger = debugger;
		this.workerName = workerName;
		this.interruptMessage = interruptMessage;
	}

	/**
	 * The dialect whose packages a function's package prefix names.
	 * @param pkg the prefix, lowercased ({@code swank-repl}, {@code slynk-mrepl})
	 * @return the dialect, or null for a package of no fork
	 */
	static @Nullable SwankDialect of(String pkg) {
		for (SwankDialect dialect : values()) {
			if (pkg.startsWith(dialect.prefix)) {
				return dialect;
			}
		}
		return null;
	}

	/**
	 * The version {@code connection-info} reports, which the client compares with its
	 * own.
	 * @return the version
	 */
	String protocolVersion() {
		return this.protocolVersion;
	}

	/**
	 * How the dialect's echo area shows a request's values.
	 * @return the echo
	 */
	CommonLispSwank.Echo echo() {
		return this.echo;
	}

	/**
	 * The modules {@code connection-info} reports loaded before any is required.
	 * @return the module names
	 */
	List<String> modules() {
		return this.modules;
	}

	/**
	 * The report of the restart {@code throw-to-toplevel} invokes.
	 * @return the report
	 */
	String topLevelReport() {
		return "Return to " + this.client + "'s top level.";
	}

	/**
	 * The report of a request's retry restart.
	 * @param request what the request is: {@code REPL evaluation},
	 * {@code interactive evaluation}, {@code evaluation}, SLY's {@code mREPL evaluation}
	 * @return the report
	 */
	String retryReport(String request) {
		return "Retry " + this.client + " " + request + " request.";
	}

	/**
	 * The report of a debugger level's own abort restart.
	 * @param level the level
	 * @return the report
	 */
	String levelReport(int level) {
		return "Return to " + this.debugger + " level " + level + ".";
	}

	/**
	 * What a thread a request starts is named.
	 * @return the name
	 */
	String workerName() {
		return this.workerName;
	}

	/**
	 * What an interrupt's debugger level reports.
	 * @return the message
	 */
	String interruptMessage() {
		return this.interruptMessage;
	}

	/**
	 * The message that makes the editor show a debugger level, after its {@code :debug}:
	 * SWANK's {@code (:debug-activate thread level nil)}, Slynk's without the nil; micros
	 * sends none.
	 * @param thread the thread's id
	 * @param level the level
	 * @return the message, or null
	 */
	@Nullable List<Object> activate(long thread, int level) {
		return switch (this) {
			case SWANK -> Arrays.asList(new SwankWire.Keyword("debug-activate"), thread, (long) level, null);
			case SLYNK -> List.of(new SwankWire.Keyword("debug-activate"), thread, (long) level);
			case MICROS -> null;
		};
	}

	/**
	 * A function's name as SWANK spells it: a fork's {@code slynk-require} or
	 * {@code slynk-macroexpand-1} is SWANK's {@code swank-require},
	 * {@code swank-macroexpand-1}, SLY's {@code sly-db-abort} SWANK's {@code sldb-abort}.
	 * @param member the function's name without its package, lowercased
	 * @return SWANK's name
	 */
	String canonical(String member) {
		if (member.startsWith(this.prefix + "-")) {
			return "swank" + member.substring(this.prefix.length());
		}
		return member.startsWith("sly-db-") ? "sldb-" + member.substring("sly-db-".length()) : member;
	}

}
