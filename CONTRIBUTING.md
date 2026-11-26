# Contributing

rontolisp is written mostly by LLMs, so pull requests are not sought. For a request or a bug,
open an issue first. A pull request whose code was written with a top frontier model (such as
Claude Fable) will be looked at; it follows the rules below.

rontolisp is licensed under the Apache License 2.0 (`LICENSE`). A contribution is accepted
under the Developer Certificate of Origin below: sign off every commit with `git commit -s`,
which appends

```
Signed-off-by: Your Name <you@example.com>
```

using the name and email of your git configuration. A pull request whose commits lack the
line fails the `DCO` check; add it to the existing commits with
`git rebase --signoff origin/develop` and force-push.

A contribution is not copied or translated from a source whose licence does not allow it:
not from OpenJDK, and not from code under a copyleft licence such as the GPL, LGPL or EPL. A
class of the Java library is written from its Javadoc and from test programs run on a JDK,
never from a JDK's implementation. `.kb/java-frontend-provenance.md` has the rules.

The Developer Certificate of Origin, Version 1.1, unchanged from
<https://developercertificate.org/>:

```text
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```
