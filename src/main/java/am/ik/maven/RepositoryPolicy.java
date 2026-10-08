package am.ik.maven;

import java.util.Objects;

/**
 * What a repository serves of one kind, releases or snapshots (Maven's {@code <releases>}
 * / {@code <snapshots>}, tools.deps' {@code :releases} / {@code :snapshots}): whether it
 * is asked at all, and when something cached from it is asked for again.
 *
 * @param enabled whether the repository is asked for this kind
 * @param update the update policy in Maven's spellings ({@code always}, {@code daily},
 * {@code never}, {@code interval:MINUTES}); see
 * {@link MavenResolver.Builder#updatePolicy}
 */
public record RepositoryPolicy(boolean enabled, String update) {

	/** Maven's default: enabled, updated daily. */
	public static final RepositoryPolicy DEFAULT = new RepositoryPolicy(true, "daily");

	/** Not asked at all. */
	public static final RepositoryPolicy DISABLED = new RepositoryPolicy(false, "daily");

	/**
	 * Validates the update policy.
	 * @param enabled whether the repository is asked for this kind
	 * @param update the update policy
	 * @throws IllegalArgumentException for a spelling Maven does not define
	 */
	public RepositoryPolicy {
		Objects.requireNonNull(update, "update");
		UpdatePolicy.parse(update);
	}

	/**
	 * The update policy, parsed.
	 * @return the policy
	 */
	UpdatePolicy updatePolicy() {
		return UpdatePolicy.parse(this.update);
	}

	/**
	 * Merges two policies as Maven Resolver merges the policies of repositories one
	 * mirror stands for ({@code DefaultRemoteRepositoryManager.merge}): a disabled one
	 * gives way to the other; two enabled ones are enabled and update as often as the
	 * more frequent.
	 * @param first the dominant policy
	 * @param second the recessive policy
	 * @return the merged policy
	 */
	static RepositoryPolicy merge(RepositoryPolicy first, RepositoryPolicy second) {
		if (!first.enabled()) {
			return second;
		}
		if (!second.enabled()) {
			return first;
		}
		return new RepositoryPolicy(true, moreFrequent(first.update(), second.update()));
	}

	/**
	 * Maven's {@code getEffectiveUpdatePolicy}: the policy with the smaller ordinal, the
	 * second on a tie ({@code always} 0, {@code interval:N} N, {@code daily} 1440,
	 * {@code never} the largest).
	 */
	private static String moreFrequent(String first, String second) {
		return ordinal(first) < ordinal(second) ? first : second;
	}

	private static int ordinal(String update) {
		UpdatePolicy policy = UpdatePolicy.parse(update);
		return switch (policy.spelling()) {
			case "daily" -> 1440;
			case "always" -> 0;
			case "never" -> Integer.MAX_VALUE;
			default -> policy.minutes();
		};
	}

}
