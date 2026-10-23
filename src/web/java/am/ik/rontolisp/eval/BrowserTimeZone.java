package am.ik.rontolisp.eval;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

/**
 * The browser's local time zone for the playground, read from JavaScript's {@code Date}
 * (used by the Web Image substitution of {@code LocalTimeZone}).
 * {@code getTimezoneOffset} gives the total offset at an instant (west-positive minutes);
 * daylight saving time is in effect when that offset is east of the year's standard one,
 * the smaller of January's and July's -- {@code Date} offers no flag for it.
 */
final class BrowserTimeZone {

	private BrowserTimeZone() {
	}

	/**
	 * The {@code %local-time-offset} encoding of the browser's zone at an instant.
	 * @param seconds the instant in Unix seconds, as decimal text
	 * @return the offset east of UTC in seconds, doubled, plus 1 under daylight saving
	 * time, as decimal text
	 */
	@JS(args = { "seconds" }, value = """
			var d = new Date(Number(seconds) * 1000);
			var east = -d.getTimezoneOffset();
			var year = d.getFullYear();
			var standard = Math.min(-new Date(year, 0, 1).getTimezoneOffset(),
			                        -new Date(year, 6, 1).getTimezoneOffset());
			return String(east * 120 + (east > standard ? 1 : 0));
			""")
	static native JSString offset(JSString seconds);

}
