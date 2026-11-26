// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class IllegalFormatConversionException extends IllegalFormatException {

    private char conversion;

    private Class<?> argument;

    // the run time's, of its message alone: the conversion, " != " and the class's name
    IllegalFormatConversionException(String message) {
        super(message);
        this.conversion = message.charAt(0);
    }

    public IllegalFormatConversionException(char c, Class<?> arg) {
        super(c + " != " + arg.getName());
        this.conversion = c;
        this.argument = arg;
    }

    public char getConversion() {
        return conversion;
    }

    public Class<?> getArgumentClass() {
        return argument;
    }
}
