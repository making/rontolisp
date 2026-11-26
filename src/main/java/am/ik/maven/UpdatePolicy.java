package am.ik.maven;

import java.util.Calendar;

/**
 * When something the local repository caches from a remote one is asked for again -- a
 * {@code maven-metadata.xml}, or an artifact no repository had: Maven's repository update
 * policy, in its spellings ({@code DefaultUpdatePolicyAnalyzer}). {@code daily} asks
 * again once local midnight has passed since the last time, {@code interval:N} after N
 * minutes, {@code always} every time a resolver asks the first time, {@code never} not at
 * all.
 *
 * @param spelling the policy as Maven writes it
 * @param minutes the interval in minutes for {@code interval:N}, else 0
 */
record UpdatePolicy(String spelling, int minutes) {

	/** Maven's default. */
	static final UpdatePolicy DAILY = new UpdatePolicy("daily", 0);

	/**
	 * Reads a policy.
	 * @param spelling {@code always}, {@code daily}, {@code never} or {@code interval:N}
	 * @return the policy
	 * @throws IllegalArgumentException for any other spelling
	 */
	static UpdatePolicy parse(String spelling) {
		switch (spelling) {
			case "always", "daily", "never" -> {
				return new UpdatePolicy(spelling, 0);
			}
			default -> {
				if (spelling.startsWith("interval:")) {
					try {
						int minutes = Integer.parseInt(spelling.substring("interval:".length()));
						if (minutes >= 0) {
							return new UpdatePolicy(spelling, minutes);
						}
					}
					catch (NumberFormatException ex) {
						// refused below
					}
				}
				throw new IllegalArgumentException(
						"not a Maven update policy (always, daily, never or interval:MINUTES): '" + spelling + "'");
			}
		}
	}

	/**
	 * Whether something last updated at {@code lastUpdated} is to be asked for again now.
	 * @param lastUpdated the time of the last update, in milliseconds since the epoch
	 * @return whether to ask again
	 */
	boolean updateRequired(long lastUpdated) {
		return switch (this.spelling) {
			case "always" -> true;
			case "never" -> false;
			case "daily" -> {
				Calendar midnight = Calendar.getInstance();
				midnight.set(Calendar.HOUR_OF_DAY, 0);
				midnight.set(Calendar.MINUTE, 0);
				midnight.set(Calendar.SECOND, 0);
				midnight.set(Calendar.MILLISECOND, 0);
				yield midnight.getTimeInMillis() > lastUpdated;
			}
			default -> {
				Calendar then = Calendar.getInstance();
				then.add(Calendar.MINUTE, -this.minutes);
				yield then.getTimeInMillis() > lastUpdated;
			}
		};
	}

	@Override
	public String toString() {
		return this.spelling;
	}

}
