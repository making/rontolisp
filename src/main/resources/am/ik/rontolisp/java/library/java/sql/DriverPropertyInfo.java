// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public class DriverPropertyInfo {

    public String name;

    public String description = null;

    public boolean required = false;

    public String value = null;

    public String[] choices = null;

    public DriverPropertyInfo(String name, String value) {
        this.name = name;
        this.value = value;
    }

}
