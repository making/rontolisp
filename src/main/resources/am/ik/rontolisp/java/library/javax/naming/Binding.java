// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

// With no class name given, the name of the bound object's class.
public class Binding extends NameClassPair {

    private Object boundObj;

    public Binding(String name, Object obj) {
        super(name, null);
        this.boundObj = obj;
    }

    public Binding(String name, Object obj, boolean isRelative) {
        super(name, null, isRelative);
        this.boundObj = obj;
    }

    public Binding(String name, String className, Object obj) {
        super(name, className);
        this.boundObj = obj;
    }

    public Binding(String name, String className, Object obj, boolean isRelative) {
        super(name, className, isRelative);
        this.boundObj = obj;
    }

    public String getClassName() {
        String named = super.getClassName();
        if (named != null) {
            return named;
        }
        return boundObj == null ? null : boundObj.getClass().getName();
    }

    public Object getObject() {
        return boundObj;
    }

    public void setObject(Object obj) {
        boundObj = obj;
    }

    public String toString() {
        return super.toString() + ":" + getObject();
    }

}
