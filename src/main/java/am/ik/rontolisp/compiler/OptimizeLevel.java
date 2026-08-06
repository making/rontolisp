package am.ik.rontolisp.compiler;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * What the CLI's {@code --optimize} is asked to optimize FOR — the single vocabulary both
 * backends read, so a level means the same thing wherever it is honoured (and is a
 * documented no-op where it has nothing to trade).
 *
 * <p>
 * <strong>The bare {@code --optimize} is {@link #DEFAULT} and always will be.</strong>
 * The flag carries a value ({@code --optimize=size}) rather than growing a second flag
 * next to it, because two flags whose names differ by one word do not say how they
 * relate: a reader hitting {@code --optimize-size} in a build script cannot tell whether
 * it replaces {@code --optimize}, adds to it, or contradicts it. A value cannot be read
 * that way, and it leaves every existing invocation — in the docs, the CI jobs, the
 * examples — meaning exactly what it meant.
 *
 * <p>
 * <strong>Levels differ in what they are willing to PAY, not in how hard they
 * try.</strong>
 *
 * <ul>
 * <li>{@link #DEFAULT} takes every win that costs nothing: dead-code elimination
 * ({@code .kb/optimize-dead-code-elimination.md}) drops what the program cannot reach,
 * and an unreachable function is not a trade-off with anything.</li>
 * <li>{@link #SIZE} additionally gives up speed for size, so it must be asked for: on the
 * wasm-GC backends it turns off the two emissions that deliberately spend bytes on speed
 * — integer expression-tree fusion ({@code .kb/wasm-int-fusion.md}), which emits every
 * fused tree twice, and unboxed dual-representation locals
 * ({@code .kb/wasm-unboxed-locals.md}).</li>
 * </ul>
 *
 * There is deliberately no third level. A level that is a synonym of another teaches a
 * reader that levels are decoration, and nothing in the compiler today is held back for
 * being too aggressive, so a "higher" level would have nothing to switch on.
 *
 * @see #parse(String)
 */
public enum OptimizeLevel {

	/**
	 * No {@code --optimize} at all: nothing is dropped and the backends emit what they
	 * emit. This is not "level 0 of an optimizer" — it is the shape the compiler has
	 * always had without the flag, and every module compiled without it stays
	 * byte-identical.
	 */
	NONE(null),

	/**
	 * The bare {@code --optimize}, spellable as {@code --optimize=default} for a build
	 * script that wants the level written down: dead-code elimination on both backends
	 * and nothing traded away for it.
	 */
	DEFAULT("default"),

	/**
	 * {@code --optimize=size}: {@link #DEFAULT} plus the speed-for-size trades. Only the
	 * wasm-GC backends (Preview 1 and {@code --component}) have any — the JVM backend and
	 * {@code --no-gc} emit the same bytes as {@link #DEFAULT}, which the docs state
	 * rather than leaving a reader to measure.
	 */
	SIZE("size");

	private final @Nullable String spelling;

	OptimizeLevel(@Nullable String spelling) {
		this.spelling = spelling;
	}

	/**
	 * The {@code --optimize=} value that selects this level, or {@code null} for
	 * {@link #NONE}, which is spelled by leaving the flag off entirely.
	 * @return the level's spelling, or {@code null}
	 */
	public @Nullable String spelling() {
		return this.spelling;
	}

	/**
	 * Whether the finished artifact is run through its backend's dead-code eliminator
	 * (the WASM tree shaker / the JVM class shaker).
	 * @return {@code true} for every level except {@link #NONE}
	 */
	public boolean eliminatesDeadCode() {
		return this != NONE;
	}

	/**
	 * Whether an emitter that can spend bytes to gain speed should decline to. Asked at
	 * the emission decision points themselves, so a backend with no such trade simply
	 * never asks and its output is unchanged.
	 * @return {@code true} only for {@link #SIZE}
	 */
	public boolean prefersSizeOverSpeed() {
		return this == SIZE;
	}

	/**
	 * Resolves a {@code --optimize} option value to a level: {@code null} (the flag is
	 * absent) is {@link #NONE} and the empty string (the bare flag, which cannot take a
	 * following-argument value without swallowing the next option) is {@link #DEFAULT}.
	 * @param value the option value as the CLI parsed it
	 * @return the level
	 * @throws IllegalArgumentException if the value names no level
	 */
	public static OptimizeLevel parse(@Nullable String value) {
		if (value == null) {
			return NONE;
		}
		if (value.isEmpty()) {
			return DEFAULT;
		}
		for (OptimizeLevel level : values()) {
			if (value.equals(level.spelling)) {
				return level;
			}
		}
		throw new IllegalArgumentException("unknown --optimize level '" + value + "' (accepted: " + spellings() + ")");
	}

	/**
	 * The accepted {@code --optimize=} values, comma-separated, for a help text or an
	 * error message.
	 * @return the spellings of every level but {@link #NONE}
	 */
	public static String spellings() {
		return Arrays.stream(values())
			.map(OptimizeLevel::spelling)
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.joining(", "));
	}

}
