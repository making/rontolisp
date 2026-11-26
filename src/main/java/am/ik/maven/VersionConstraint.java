package am.ik.maven;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

/**
 * A version as a dependency writes it, read the way Maven Resolver's
 * {@code GenericVersionScheme.parseVersionConstraint} reads it: either one version
 * ({@code 1.0}, compared by {@link GenericVersion}) or a union of ranges --
 * {@code [1.0,2.0)}, {@code (,1.0]}, {@code [1.0]} (exactly 1.0), {@code [1.*]} (every
 * 1.x), several separated by commas ({@code [1,2),[3,4)}), a version contained when any
 * range contains it.
 */
final class VersionConstraint {

	/**
	 * One end of a range.
	 *
	 * @param version the version
	 * @param inclusive whether the version itself is in the range
	 */
	record Bound(GenericVersion version, boolean inclusive) {
	}

	/**
	 * One range; a missing bound is open.
	 *
	 * @param lower the lower bound, or {@code null}
	 * @param upper the upper bound, or {@code null}
	 */
	record Range(@Nullable Bound lower, @Nullable Bound upper) {

		boolean contains(GenericVersion version) {
			if (this.lower != null) {
				int comparison = this.lower.version().compareTo(version);
				if (comparison > 0 || (comparison == 0 && !this.lower.inclusive())) {
					return false;
				}
			}
			if (this.upper != null) {
				int comparison = this.upper.version().compareTo(version);
				if (comparison < 0 || (comparison == 0 && !this.upper.inclusive())) {
					return false;
				}
			}
			return true;
		}

		@Override
		public String toString() {
			StringBuilder text = new StringBuilder(32);
			if (this.lower != null) {
				text.append(this.lower.inclusive() ? '[' : '(').append(this.lower.version());
			}
			else {
				text.append('(');
			}
			text.append(',');
			if (this.upper != null) {
				text.append(this.upper.version()).append(this.upper.inclusive() ? ']' : ')');
			}
			else {
				text.append(')');
			}
			return text.toString();
		}

	}

	private final String text;

	private final @Nullable GenericVersion version;

	private final Set<Range> ranges;

	private final @Nullable Bound lowerBound;

	private final @Nullable Bound upperBound;

	private VersionConstraint(String text, @Nullable GenericVersion version, Set<Range> ranges) {
		this.text = text;
		this.version = version;
		this.ranges = ranges;
		// UnionVersionRange: the lowest lower bound and the highest upper bound, an open
		// end of any range opening the union's
		Bound lower = null;
		for (Range range : ranges) {
			Bound bound = range.lower();
			if (bound == null) {
				lower = null;
				break;
			}
			if (lower == null) {
				lower = bound;
				continue;
			}
			int c = bound.version().compareTo(lower.version());
			if (c < 0 || (c == 0 && !lower.inclusive())) {
				lower = bound;
			}
		}
		Bound upper = null;
		for (Range range : ranges) {
			Bound bound = range.upper();
			if (bound == null) {
				upper = null;
				break;
			}
			if (upper == null) {
				upper = bound;
				continue;
			}
			int c = bound.version().compareTo(upper.version());
			if (c > 0 || (c == 0 && !upper.inclusive())) {
				upper = bound;
			}
		}
		this.lowerBound = lower;
		this.upperBound = upper;
	}

	/**
	 * Whether a version as written is a range rather than one version: it starts with
	 * {@code [} or {@code (}.
	 * @param version the version
	 * @return whether it is a range
	 */
	static boolean isRange(String version) {
		return version.startsWith("[") || version.startsWith("(");
	}

	/**
	 * Reads a constraint.
	 * @param constraint the text
	 * @return the constraint
	 * @throws MavenResolutionException if the text is not a valid range, in Maven's words
	 */
	static VersionConstraint parse(String constraint) throws MavenResolutionException {
		String process = constraint;
		List<Range> ranges = new ArrayList<>();
		while (process.startsWith("[") || process.startsWith("(")) {
			int index1 = process.indexOf(')');
			int index2 = process.indexOf(']');
			int index = index2;
			if (index2 < 0 || (index1 >= 0 && index1 < index2)) {
				index = index1;
			}
			if (index < 0) {
				throw new MavenResolutionException("Unbounded version range " + constraint);
			}
			ranges.add(range(process.substring(0, index + 1)));
			process = process.substring(index + 1).trim();
			if (process.startsWith(",")) {
				process = process.substring(1).trim();
			}
		}
		if (!process.isEmpty() && !ranges.isEmpty()) {
			throw new MavenResolutionException(
					"Invalid version range " + constraint + ", expected [ or ( but got " + process);
		}
		if (ranges.isEmpty()) {
			return new VersionConstraint(constraint, GenericVersion.parse(constraint), Set.of());
		}
		return new VersionConstraint(constraint, null, new LinkedHashSet<>(ranges));
	}

	private static Range range(String range) throws MavenResolutionException {
		boolean lowerInclusive = range.startsWith("[");
		if (!lowerInclusive && !range.startsWith("(")) {
			throw new MavenResolutionException(
					"Invalid version range " + range + ", a range must start with either [ or (");
		}
		boolean upperInclusive = range.endsWith("]");
		if (!upperInclusive && !range.endsWith(")")) {
			throw new MavenResolutionException(
					"Invalid version range " + range + ", a range must end with either [ or (");
		}
		String process = range.substring(1, range.length() - 1);
		int index = process.indexOf(',');
		GenericVersion lower;
		GenericVersion upper;
		if (index < 0) {
			if (!lowerInclusive || !upperInclusive) {
				throw new MavenResolutionException(
						"Invalid version range " + range + ", single version must be surrounded by []");
			}
			String version = process.trim();
			if (version.endsWith(".*")) {
				String prefix = version.substring(0, version.length() - 1);
				lower = GenericVersion.parse(prefix + "min");
				upper = GenericVersion.parse(prefix + "max");
			}
			else {
				lower = GenericVersion.parse(version);
				upper = lower;
			}
		}
		else {
			String lowerText = process.substring(0, index).trim();
			String upperText = process.substring(index + 1).trim();
			if (upperText.contains(",")) {
				throw new MavenResolutionException(
						"Invalid version range " + range + ", bounds may not contain additional ','");
			}
			lower = lowerText.isEmpty() ? null : GenericVersion.parse(lowerText);
			upper = upperText.isEmpty() ? null : GenericVersion.parse(upperText);
			if (lower != null && upper != null && upper.compareTo(lower) < 0) {
				throw new MavenResolutionException(
						"Invalid version range " + range + ", lower bound must not be greater than upper bound");
			}
		}
		return new Range(lower == null ? null : new Bound(lower, lowerInclusive),
				upper == null ? null : new Bound(upper, upperInclusive));
	}

	/**
	 * Whether this is a range (or a union of them) rather than one version.
	 * @return whether it is a range
	 */
	boolean isRange() {
		return this.version == null;
	}

	/**
	 * Whether a version satisfies this constraint: equal to its version, or inside one of
	 * its ranges.
	 * @param candidate the version
	 * @return whether it is contained
	 */
	boolean contains(GenericVersion candidate) {
		if (this.version != null) {
			return this.version.equals(candidate);
		}
		for (Range range : this.ranges) {
			if (range.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The union's lower bound.
	 * @return the bound, or {@code null} when open (or not a range)
	 */
	@Nullable Bound lowerBound() {
		return this.lowerBound;
	}

	/**
	 * The union's upper bound.
	 * @return the bound, or {@code null} when open (or not a range)
	 */
	@Nullable Bound upperBound() {
		return this.upperBound;
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof VersionConstraint that && Objects.equals(this.version, that.version)
				&& this.ranges.equals(that.ranges);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.version, this.ranges);
	}

	/**
	 * The constraint as Maven Resolver prints it: a version as written; each range with
	 * both bounds spelled out ({@code [1.0]} is {@code [1.0,1.0]}, {@code [1.*]} is
	 * {@code [1.min,1.max]}), several joined by {@code ", "} in the order written
	 * (Maven's own order is its hash set's).
	 * @return the text
	 */
	@Override
	public String toString() {
		if (this.version != null) {
			return this.text;
		}
		StringJoiner joined = new StringJoiner(", ");
		for (Range range : this.ranges) {
			joined.add(range.toString());
		}
		return joined.toString();
	}

}
