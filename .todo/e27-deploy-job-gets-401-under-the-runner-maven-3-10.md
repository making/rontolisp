# e27. The CI `deploy` job's snapshot publish gets HTTP 401 under the runner's Maven 3.10.0

Difficulty: Medium

`.github/workflows/ci.yaml` `deploy:` runs `/usr/bin/mvn`, not `./mvnw`. Every deploy on runner
image `ubuntu-24.04` 20261004.327 (`/usr/bin/mvn` 3.10.0) failed on 2026-10-07/08 (runs
37623701558, 37668622074, 37702169338); every one on 20260927.320 (Maven 3.9.16) passed
(37619209902, 37628407897). The error, same credentials both ways:

    central-publishing-maven-plugin:0.11.0:publish (injected-central-publishing) on project
    rontolisp: ... Could not transfer artifact am.ik.rontolisp:rontolisp:jar.asc:...
    from/to central (https://central.sonatype.com/repository/maven-snapshots/): HTTP Status: 401

Find what 3.10.0 changes for the `central` server credentials that `s4u/maven-settings-action`
writes, then decide between running the deploy on `./mvnw` (the Maven every other job uses)
and adapting to 3.10. The `maven-plugin` job had the same trigger (`.kb/session-workflow.md`).
