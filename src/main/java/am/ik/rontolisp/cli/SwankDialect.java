package am.ik.rontolisp.cli;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.eval.CommonLispSwank;

/**
 * The fork of SWANK's protocol a request speaks, told by the package prefix of the
 * function it calls: SLIME's {@code swank}, SLY's {@code slynk} ({@code slynk-mrepl},
 * {@code slynk-completion} ...), Lem's {@code micros} ({@code micros/repl},
 * {@code micros/lsp-api} ...). The framing and {@code :emacs-rex} are the same; a fork
 * names its functions in its own packages, reports its own protocol version and module
 * names, and answers a few requests in shapes of its own.
 */
enum SwankDialect {

	/** SLIME's SWANK, measured against SLIME 2.32. */
	SWANK("swank", "2.32", List.of("SWANK-REPL", "SWANK-ARGLISTS", "SWANK-C-P-C", "SWANK-FUZZY", "SWANK-UTIL"),
			CommonLispSwank.Echo.SWANK),

	/** SLY's Slynk, measured against SLY 1.0.43. */
	SLYNK("slynk", "1.0.43", List.of("SLYNK/COMPLETION"), CommonLispSwank.Echo.SLYNK),

	/** Lem's micros, measured against Lem 2.3.0 with micros 2ec61c8. */
	MICROS("micros", "0.0.0", List.of(), CommonLispSwank.Echo.MICROS);

	private final String prefix;

	private final String protocolVersion;

	private final List<String> modules;

	private final CommonLispSwank.Echo echo;

	SwankDialect(String prefix, String protocolVersion, List<String> modules, CommonLispSwank.Echo echo) {
		this.prefix = prefix;
		this.protocolVersion = protocolVersion;
		this.modules = modules;
		this.echo = echo;
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
	 * A function's name as SWANK spells it: a fork's {@code slynk-require} or
	 * {@code slynk-macroexpand-1} is SWANK's {@code swank-require},
	 * {@code swank-macroexpand-1}.
	 * @param member the function's name without its package, lowercased
	 * @return SWANK's name
	 */
	String canonical(String member) {
		return member.startsWith(this.prefix + "-") ? "swank" + member.substring(this.prefix.length()) : member;
	}

}
