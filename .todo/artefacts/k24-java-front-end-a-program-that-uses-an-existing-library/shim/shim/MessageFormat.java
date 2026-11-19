package shim;

import java.util.Locale;

// Stands where java.text.MessageFormat is named, for the measurement only: {n} is the
// text of the argument, and nothing else of a pattern is read.
public class MessageFormat {

    private final String pattern;

    public MessageFormat(String pattern, Locale locale) {
        this.pattern = pattern;
    }

    public String format(Object[] arguments) {
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < this.pattern.length()) {
            char c = this.pattern.charAt(i);
            int close = c == '{' ? this.pattern.indexOf('}', i) : -1;
            if (close > i) {
                int index = Integer.parseInt(this.pattern.substring(i + 1, close));
                text.append(index < arguments.length ? String.valueOf(arguments[index]) : "{" + index + "}");
                i = close + 1;
            }
            else {
                text.append(c);
                i++;
            }
        }
        return text.toString();
    }
}
