# e48. Maven resolver: settings.xml mirrors, proxies and credentials

Difficulty: Medium

`am.ik.maven` refuses by name a repository a `settings.xml` mirror or proxy covers, and never
sends `<server>` credentials (`.kb/maven-resolver.md`, "Repositories"). A corporate setup --
everything through a Nexus mirror, an HTTP proxy, an authenticated repository -- resolves
nothing that is not already in the local repository.

## Scope

- Mirrors: route a repository to its mirror (`MavenSettings.mirrorFor` already matches as
  Maven's `DefaultMirrorSelector`); the mirror's id then names the credentials; `<blocked>`.
- Proxies: `HttpDownloader` through a proxy with its credentials, `nonProxyHosts`
  (`MavenSettings.proxyFor` already selects as Maven does).
- Credentials: Basic auth from `<server>` username/password; an encrypted password
  (`settings-security.xml`) and `<configuration><httpHeaders>`: support or refuse by name.
- The global settings file (`$MAVEN_HOME/conf/settings.xml`): decide whether and how to find it.
- Tests against a local `HttpServer` acting as proxy and authenticated repository; no network.
