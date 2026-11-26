package am.ik.servlet.httpserver;

/**
 * The date of an HTTP field: written in the fixed form of RFC 9110 5.6.7 and read in its
 * three forms. Written by hand over a count of days, so that it asks nothing of
 * {@code java.time} or {@code java.text}.
 */
final class HttpDates {

	private static final String[] DAYS = { "Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed" };

	private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
			"Nov", "Dec" };

	private HttpDates() {
	}

	/**
	 * Writes a time as {@code Sun, 06 Nov 1994 08:49:37 GMT}.
	 * @param millis milliseconds since the epoch
	 * @return the date in the fixed form
	 */
	static String format(long millis) {
		long seconds = Math.floorDiv(millis, 1000L);
		long days = Math.floorDiv(seconds, 86400L);
		int rest = (int) Math.floorMod(seconds, 86400L);
		// The civil date of a count of days, by eras of 400 years starting on March 1.
		long shifted = days + 719468L;
		long era = Math.floorDiv(shifted, 146097L);
		int dayOfEra = (int) Math.floorMod(shifted, 146097L);
		int yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365;
		int dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
		int shiftedMonth = (5 * dayOfYear + 2) / 153;
		int day = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1;
		int month = shiftedMonth < 10 ? shiftedMonth + 3 : shiftedMonth - 9;
		long year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0);
		StringBuilder text = new StringBuilder(29);
		text.append(DAYS[(int) Math.floorMod(days, 7L)]).append(", ");
		two(text, day).append(' ').append(MONTHS[month - 1]).append(' ');
		if (year < 1000 && year >= 0) {
			text.append(year < 10 ? "000" : year < 100 ? "00" : "0");
		}
		text.append(year).append(' ');
		two(text, rest / 3600).append(':');
		two(text, rest / 60 % 60).append(':');
		two(text, rest % 60).append(" GMT");
		return text.toString();
	}

	/**
	 * Reads a date in the fixed form, the form of RFC 850 or the form of asctime.
	 * @param text the field's value
	 * @return milliseconds since the epoch, or -1 where the text is none of the three
	 */
	static long parse(String text) {
		// Every form is a run of numbers and one month name; the weekday is not checked.
		int[] numbers = new int[6];
		int count = 0;
		int month = -1;
		int length = text.length();
		int at = 0;
		while (at < length) {
			char c = text.charAt(at);
			if (c >= '0' && c <= '9') {
				int value = 0;
				int digits = 0;
				while (at < length && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
					value = value * 10 + (text.charAt(at) - '0');
					at++;
					if (++digits > 4) {
						return -1;
					}
				}
				if (count == numbers.length) {
					return -1;
				}
				numbers[count++] = value;
			}
			else if (isLetter(c)) {
				int start = at;
				while (at < length && isLetter(text.charAt(at))) {
					at++;
				}
				if (at - start == 3 && month < 0) {
					month = month(text, start);
				}
			}
			else {
				at++;
			}
		}
		if (month < 0 || count != 5) {
			return -1;
		}
		int day;
		int year;
		int hour;
		int minute;
		int second;
		if (numbers[4] > 99 && numbers[0] <= 31) {
			// asctime: Sun Nov 6 08:49:37 1994
			day = numbers[0];
			hour = numbers[1];
			minute = numbers[2];
			second = numbers[3];
			year = numbers[4];
		}
		else {
			day = numbers[0];
			year = numbers[1];
			hour = numbers[2];
			minute = numbers[3];
			second = numbers[4];
			if (year < 100 && text.indexOf('-') >= 0) {
				// RFC 850's two digits: a year of the century it most likely means.
				year += year < 70 ? 2000 : 1900;
			}
		}
		if (day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) {
			return -1;
		}
		int shiftedYear = month < 2 ? year - 1 : year;
		long era = Math.floorDiv((long) shiftedYear, 400L);
		int yearOfEra = (int) (shiftedYear - era * 400);
		int dayOfYear = (153 * (month < 2 ? month + 10 : month - 2) + 2) / 5 + day - 1;
		int dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
		long days = era * 146097L + dayOfEra - 719468L;
		return (days * 86400L + hour * 3600L + minute * 60L + second) * 1000L;
	}

	private static boolean isLetter(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private static int month(String text, int start) {
		for (int i = 0; i < MONTHS.length; i++) {
			if (text.regionMatches(true, start, MONTHS[i], 0, 3)) {
				return i;
			}
		}
		return -1;
	}

	private static StringBuilder two(StringBuilder text, int value) {
		if (value < 10) {
			text.append('0');
		}
		return text.append(value);
	}

}
