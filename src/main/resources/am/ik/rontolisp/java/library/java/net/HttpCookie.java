// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A cookie of a Set-Cookie field. What the specification leaves open was read off a
// JDK 25's answers (java-spec.yaml, the cookies' case): the version a header is read
// as (an expires= in it makes it 0, else a version= or a max-age makes it 1, else its
// set-cookie2: prefix), a header of version 1 cut at its commas outside quotes, an
// attribute given twice the first one's, the max-age an Expires is read as, the words
// of each refusal, the characters a name refuses, domainMatches over the names asked.
package java.net;

import java.util.ArrayList;
import java.util.List;

public final class HttpCookie implements Cloneable {

    private final String name;

    private String value;

    private String comment;

    private String commentURL;

    private boolean discard;

    private String domain;

    private long maxAge = -1;

    private String path;

    private String portlist;

    private boolean secure;

    private boolean httpOnly;

    private int version = 1;

    // when the cookie was made: its max-age counts from here
    private final long created;

    public HttpCookie(String name, String value) {
        this(legal(name.trim()), value, System.currentTimeMillis());
    }

    private HttpCookie(String name, String value, long created) {
        this.name = name;
        this.value = value;
        this.created = created;
    }

    private static String legal(String name) {
        boolean illegal = name.isEmpty() || name.charAt(0) == '$';
        for (int i = 0; i < name.length() && !illegal; i++) {
            char c = name.charAt(i);
            illegal = c <= ' ' || c >= 0x7f || c == ',' || c == ';';
        }
        if (illegal) {
            throw new IllegalArgumentException("Illegal cookie name");
        }
        return name;
    }

    public static List<HttpCookie> parse(String header) {
        int version = versionOf(header.toLowerCase());
        String text = header;
        if (header.regionMatches(true, 0, "set-cookie2:", 0, 12)) {
            text = header.substring(12);
        }
        else if (header.regionMatches(true, 0, "set-cookie:", 0, 11)) {
            text = header.substring(11);
        }
        List<HttpCookie> cookies = new ArrayList<>();
        if (version == 0) {
            cookies.add(one(text, 0));
            return cookies;
        }
        // version 1: one cookie between two commas outside quotes
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            else if (c == ',' && !quoted) {
                cookies.add(one(text.substring(start, i), 1));
                start = i + 1;
            }
        }
        cookies.add(one(text.substring(start), 1));
        return cookies;
    }

    private static int versionOf(String lower) {
        if (lower.contains("expires=")) {
            return 0;
        }
        if (lower.contains("version=") || lower.contains("max-age")) {
            return 1;
        }
        return lower.startsWith("set-cookie2:") ? 1 : 0;
    }

    private static HttpCookie one(String text, int version) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Empty cookie header string");
        }
        int semicolon = text.indexOf(';');
        String pair = semicolon < 0 ? text : text.substring(0, semicolon);
        int equals = pair.indexOf('=');
        if (equals < 0) {
            throw new IllegalArgumentException("Invalid cookie name-value pair");
        }
        HttpCookie cookie = new HttpCookie(pair.substring(0, equals).trim(),
                unquoted(pair.substring(equals + 1).trim()));
        while (semicolon >= 0) {
            int next = text.indexOf(';', semicolon + 1);
            String attribute = next < 0 ? text.substring(semicolon + 1) : text.substring(semicolon + 1, next);
            int at = attribute.indexOf('=');
            String attributeName = (at < 0 ? attribute : attribute.substring(0, at)).trim();
            String attributeValue = at < 0 ? null : unquoted(attribute.substring(at + 1).trim());
            cookie.attribute(attributeName.toLowerCase(), attributeValue);
            semicolon = next;
        }
        cookie.version = version;
        return cookie;
    }

    private static String unquoted(String text) {
        if (text.length() > 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    // An attribute of a Set-Cookie field: the first of a name given twice is kept, and a
    // max-age of -1 is none yet.
    private void attribute(String attribute, String text) {
        switch (attribute) {
            case "comment" -> {
                if (comment == null) {
                    comment = text;
                }
            }
            case "commenturl" -> {
                if (commentURL == null) {
                    commentURL = text;
                }
            }
            case "discard" -> discard = true;
            case "domain" -> {
                if (domain == null && text != null) {
                    setDomain(text);
                }
            }
            case "expires" -> {
                if (maxAge == -1) {
                    maxAge = secondsUntil(text);
                }
            }
            case "httponly" -> httpOnly = true;
            case "max-age" -> {
                long seconds;
                try {
                    seconds = Long.parseLong(text);
                }
                catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Illegal cookie max-age attribute");
                }
                if (maxAge == -1) {
                    maxAge = seconds;
                }
            }
            case "path" -> {
                if (path == null) {
                    path = text;
                }
            }
            case "port" -> {
                if (portlist == null) {
                    portlist = text == null ? "" : text;
                }
            }
            case "secure" -> secure = true;
            case "version" -> {
                // checked, not taken: the version is the header's
                if (text != null) {
                    try {
                        setVersion(Integer.parseInt(text));
                    }
                    catch (NumberFormatException ex) {
                        // a version that is no number is let be
                    }
                }
            }
            default -> {
            }
        }
    }

    // The seconds from now to an Expires date, 0 for one past or not read.
    private long secondsUntil(String text) {
        long at = text == null ? Long.MIN_VALUE : expiry(text);
        if (at == Long.MIN_VALUE) {
            return 0;
        }
        long seconds = (at - created) / 1000;
        return seconds > 0 ? seconds : 0;
    }

    private static final String[] DAYS = { "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
            "sunday" };

    private static final String[] MONTHS = { "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december" };

    /**
     * The milliseconds of an Expires date, Long.MIN_VALUE for one not read. The forms a
     * JDK 25 was seen to read: a day's name, a comma and a space, the day, a space or a
     * dash, a month's name, the same again, the year, a space, the time, a space and
     * GMT, anything after it let be; a name whole or of three letters in any case, a
     * number of any digits with spaces before it, the day of the week the date's own.
     */
    private static long expiry(String text) {
        int[] at = { 0 };
        int day = named(text, at, DAYS);
        if (day < 0 || !text.startsWith(", ", at[0])) {
            return Long.MIN_VALUE;
        }
        at[0] += 2;
        int date = number(text, at);
        char separator = at[0] < text.length() ? text.charAt(at[0]) : 0;
        if (date < 0 || (separator != ' ' && separator != '-')) {
            return Long.MIN_VALUE;
        }
        at[0]++;
        int month = named(text, at, MONTHS);
        if (month < 0 || at[0] >= text.length() || text.charAt(at[0]) != separator) {
            return Long.MIN_VALUE;
        }
        at[0]++;
        int year = number(text, at);
        if (year < 0 || !text.startsWith(" ", at[0])) {
            return Long.MIN_VALUE;
        }
        at[0]++;
        int hour = number(text, at);
        if (hour < 0 || !text.startsWith(":", at[0])) {
            return Long.MIN_VALUE;
        }
        at[0]++;
        int minute = number(text, at);
        if (minute < 0 || !text.startsWith(":", at[0])) {
            return Long.MIN_VALUE;
        }
        at[0]++;
        int second = number(text, at);
        if (second < 0 || !text.startsWith(" GMT", at[0]) || hour > 23 || minute > 59 || second > 59 || date < 1
                || date > daysIn(year, month)) {
            return Long.MIN_VALUE;
        }
        long days = epochDay(year, month, date);
        if (Math.floorMod(days + 3, 7) != day) {
            return Long.MIN_VALUE;
        }
        return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L;
    }

    // The index of the name at at[0], whole or of its first three letters, -1 for none;
    // at[0] moves past it.
    private static int named(String text, int[] at, String[] names) {
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (text.regionMatches(true, at[0], name, 0, name.length())) {
                at[0] += name.length();
                return i;
            }
        }
        for (int i = 0; i < names.length; i++) {
            if (text.regionMatches(true, at[0], names[i], 0, 3)) {
                at[0] += 3;
                return i;
            }
        }
        return -1;
    }

    // The digits at at[0] after any spaces, -1 for none; at[0] moves past them.
    private static int number(String text, int[] at) {
        int i = at[0];
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        int start = i;
        long value = 0;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            value = value * 10 + (text.charAt(i) - '0');
            if (value > Integer.MAX_VALUE) {
                return -1;
            }
            i++;
        }
        at[0] = i;
        return i == start ? -1 : (int) value;
    }

    private static boolean leap(long year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    private static int daysIn(int year, int month) {
        if (month == 1) {
            return leap(year) ? 29 : 28;
        }
        return month == 3 || month == 5 || month == 8 || month == 10 ? 30 : 31;
    }

    // The days from 1970-01-01 to a date of the Gregorian calendar, month from 0.
    private static long epochDay(int year, int month, int date) {
        long y = year - 1;
        long days = y * 365 + Math.floorDiv(y, 4) - Math.floorDiv(y, 100) + Math.floorDiv(y, 400);
        for (int m = 0; m < month; m++) {
            days += daysIn(year, m);
        }
        // 719162 days from 0001-01-01 to 1970-01-01
        return days + date - 1 - 719162;
    }

    /** Whether a domain has a dot neither first nor last (RFC 2965, 3.3.2: an embedded dot). */
    static boolean dotted(String domain) {
        int dot = domain.indexOf('.', 1);
        return dot > 0 && dot < domain.length() - 1;
    }

    public boolean hasExpired() {
        if (maxAge == 0) {
            return true;
        }
        return maxAge > 0 && (System.currentTimeMillis() - created) / 1000 > maxAge;
    }

    public void setComment(String purpose) {
        this.comment = purpose;
    }

    public String getComment() {
        return comment;
    }

    public void setCommentURL(String purpose) {
        this.commentURL = purpose;
    }

    public String getCommentURL() {
        return commentURL;
    }

    public void setDiscard(boolean discard) {
        this.discard = discard;
    }

    public boolean getDiscard() {
        return discard;
    }

    public void setPortlist(String ports) {
        this.portlist = ports;
    }

    public String getPortlist() {
        return portlist;
    }

    public void setDomain(String pattern) {
        this.domain = pattern == null ? null : pattern.toLowerCase();
    }

    public String getDomain() {
        return domain;
    }

    public void setMaxAge(long expiry) {
        this.maxAge = expiry;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public void setPath(String uri) {
        this.path = uri;
    }

    public String getPath() {
        return path;
    }

    public void setSecure(boolean flag) {
        this.secure = flag;
    }

    public boolean getSecure() {
        return secure;
    }

    public String getName() {
        return name;
    }

    public void setValue(String newValue) {
        this.value = newValue;
    }

    public String getValue() {
        return value;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int v) {
        if (v != 0 && v != 1) {
            throw new IllegalArgumentException("cookie version should be 0 or 1");
        }
        this.version = v;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public void setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    public static boolean domainMatches(String domain, String host) {
        if (domain == null || host == null) {
            return false;
        }
        if (!dotted(domain) && !domain.equalsIgnoreCase(".local")) {
            return false;
        }
        if (domain.charAt(0) == '.' && host.equalsIgnoreCase(domain.substring(1))) {
            return true;
        }
        // a host of one label is read as one under .local
        String effective = host.indexOf('.') < 0 ? host + ".local" : host;
        int rest = effective.length() - domain.length();
        return rest >= 0 && effective.regionMatches(true, rest, domain, 0, domain.length())
                && effective.lastIndexOf('.', rest - 1) < 0;
    }

    public String toString() {
        if (version == 0) {
            return name + "=" + value;
        }
        StringBuilder text = new StringBuilder();
        text.append(name).append("=\"").append(value).append('"');
        if (path != null) {
            text.append(";$Path=\"").append(path).append('"');
        }
        if (domain != null) {
            text.append(";$Domain=\"").append(domain).append('"');
        }
        if (portlist != null) {
            text.append(";$Port=\"").append(portlist).append('"');
        }
        return text.toString();
    }

    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof HttpCookie other)) {
            return false;
        }
        return name.equalsIgnoreCase(other.name)
                && (domain == null ? other.domain == null : domain.equalsIgnoreCase(other.domain))
                && (path == null ? other.path == null : path.equals(other.path));
    }

    public int hashCode() {
        return name.toLowerCase().hashCode() + (domain == null ? 0 : domain.toLowerCase().hashCode())
                + (path == null ? 0 : path.hashCode());
    }

    public Object clone() {
        HttpCookie copy = new HttpCookie(name, value, created);
        copy.comment = comment;
        copy.commentURL = commentURL;
        copy.discard = discard;
        copy.domain = domain;
        copy.maxAge = maxAge;
        copy.path = path;
        copy.portlist = portlist;
        copy.secure = secure;
        copy.httpOnly = httpOnly;
        copy.version = version;
        return copy;
    }
}
