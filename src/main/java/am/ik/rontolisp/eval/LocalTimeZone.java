package am.ik.rontolisp.eval;

import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRules;

/**
 * The interpreter's local time zone: the answer of {@code %local-time-offset}, which
 * {@code decode-universal-time}, {@code encode-universal-time} and
 * {@code get-decoded-time} consult when no time-zone is given. The zone is the JVM's
 * default ({@link ZoneId#systemDefault()}), so the {@code TZ} environment variable and
 * {@code -Duser.timezone} are honored as the JVM honors them. The JVM backend emits the
 * same three calls into the compiled class ({@code JvmLocalTimeRuntimeBuilder}); the
 * browser playground substitutes this class with JavaScript's {@code Date}
 * ({@code Target_LocalTimeZone}).
 */
final class LocalTimeZone {

	private LocalTimeZone() {
	}

	/**
	 * The local zone at an instant, in the {@code %local-time-offset} encoding.
	 * @param unixSeconds the instant as seconds since 1970-01-01 00:00:00 UTC
	 * @return the total offset east of UTC in seconds, doubled, plus 1 when daylight
	 * saving time is in effect
	 */
	static long offset(long unixSeconds) {
		Instant instant = Instant.ofEpochSecond(unixSeconds);
		ZoneRules rules = ZoneId.systemDefault().getRules();
		return rules.getOffset(instant).getTotalSeconds() * 2L + (rules.isDaylightSavings(instant) ? 1 : 0);
	}

}
