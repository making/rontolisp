// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

import java.io.Serializable;
import java.util.Enumeration;
import java.util.Vector;

// A class's name, its factory and its addresses in their order. An address is looked for
// by its type's compareTo, as a JDK does, so a null type is refused where it is met.
public class Reference implements Cloneable, Serializable {

    protected String className;

    protected Vector<RefAddr> addrs = new Vector<>();

    protected String classFactory;

    protected String classFactoryLocation;

    public Reference(String className) {
        this.className = className;
    }

    public Reference(String className, RefAddr addr) {
        this.className = className;
        addrs.addElement(addr);
    }

    public Reference(String className, String factory, String factoryLocation) {
        this.className = className;
        this.classFactory = factory;
        this.classFactoryLocation = factoryLocation;
    }

    public Reference(String className, RefAddr addr, String factory, String factoryLocation) {
        this(className, addr);
        this.classFactory = factory;
        this.classFactoryLocation = factoryLocation;
    }

    public String getClassName() {
        return className;
    }

    public String getFactoryClassName() {
        return classFactory;
    }

    public String getFactoryClassLocation() {
        return classFactoryLocation;
    }

    public RefAddr get(String addrType) {
        for (int i = 0; i < addrs.size(); i++) {
            RefAddr addr = addrs.elementAt(i);
            if (addr.getType().compareTo(addrType) == 0) {
                return addr;
            }
        }
        return null;
    }

    public RefAddr get(int posn) {
        return addrs.elementAt(posn);
    }

    public Enumeration<RefAddr> getAll() {
        return addrs.elements();
    }

    public int size() {
        return addrs.size();
    }

    public void add(RefAddr addr) {
        addrs.addElement(addr);
    }

    public void add(int posn, RefAddr addr) {
        addrs.insertElementAt(addr, posn);
    }

    public Object remove(int posn) {
        RefAddr removed = addrs.elementAt(posn);
        addrs.removeElementAt(posn);
        return removed;
    }

    public void clear() {
        addrs.removeAllElements();
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof Reference other) || !other.className.equals(className) || other.size() != size()) {
            return false;
        }
        for (int i = 0; i < addrs.size(); i++) {
            if (!addrs.elementAt(i).equals(other.addrs.elementAt(i))) {
                return false;
            }
        }
        return true;
    }

    public int hashCode() {
        int hash = className.hashCode();
        for (int i = 0; i < addrs.size(); i++) {
            hash += addrs.elementAt(i).hashCode();
        }
        return hash;
    }

    public String toString() {
        StringBuilder text = new StringBuilder("Reference Class Name: ").append(className).append('\n');
        for (int i = 0; i < addrs.size(); i++) {
            text.append(addrs.elementAt(i).toString());
        }
        return text.toString();
    }

    // A Reference whatever the class, holding the same addresses in a list of its own.
    public Object clone() {
        Reference copy = new Reference(className, classFactory, classFactoryLocation);
        for (int i = 0; i < addrs.size(); i++) {
            copy.addrs.addElement(addrs.elementAt(i));
        }
        return copy;
    }

}
