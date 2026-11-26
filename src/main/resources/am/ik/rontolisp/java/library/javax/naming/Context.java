// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

import java.util.Hashtable;

public interface Context {

    String INITIAL_CONTEXT_FACTORY = "java.naming.factory.initial";

    String OBJECT_FACTORIES = "java.naming.factory.object";

    String STATE_FACTORIES = "java.naming.factory.state";

    String URL_PKG_PREFIXES = "java.naming.factory.url.pkgs";

    String PROVIDER_URL = "java.naming.provider.url";

    String DNS_URL = "java.naming.dns.url";

    String AUTHORITATIVE = "java.naming.authoritative";

    String BATCHSIZE = "java.naming.batchsize";

    String REFERRAL = "java.naming.referral";

    String SECURITY_PROTOCOL = "java.naming.security.protocol";

    String SECURITY_AUTHENTICATION = "java.naming.security.authentication";

    String SECURITY_PRINCIPAL = "java.naming.security.principal";

    String SECURITY_CREDENTIALS = "java.naming.security.credentials";

    String LANGUAGE = "java.naming.language";

    Object lookup(Name name) throws NamingException;

    Object lookup(String name) throws NamingException;

    void bind(Name name, Object obj) throws NamingException;

    void bind(String name, Object obj) throws NamingException;

    void rebind(Name name, Object obj) throws NamingException;

    void rebind(String name, Object obj) throws NamingException;

    void unbind(Name name) throws NamingException;

    void unbind(String name) throws NamingException;

    void rename(Name oldName, Name newName) throws NamingException;

    void rename(String oldName, String newName) throws NamingException;

    NamingEnumeration<NameClassPair> list(Name name) throws NamingException;

    NamingEnumeration<NameClassPair> list(String name) throws NamingException;

    NamingEnumeration<Binding> listBindings(Name name) throws NamingException;

    NamingEnumeration<Binding> listBindings(String name) throws NamingException;

    void destroySubcontext(Name name) throws NamingException;

    void destroySubcontext(String name) throws NamingException;

    Context createSubcontext(Name name) throws NamingException;

    Context createSubcontext(String name) throws NamingException;

    Object lookupLink(Name name) throws NamingException;

    Object lookupLink(String name) throws NamingException;

    NameParser getNameParser(Name name) throws NamingException;

    NameParser getNameParser(String name) throws NamingException;

    Name composeName(Name name, Name prefix) throws NamingException;

    String composeName(String name, String prefix) throws NamingException;

    Object addToEnvironment(String propName, Object propVal) throws NamingException;

    Object removeFromEnvironment(String propName) throws NamingException;

    Hashtable<?, ?> getEnvironment() throws NamingException;

    void close() throws NamingException;

    String getNameInNamespace() throws NamingException;

}
