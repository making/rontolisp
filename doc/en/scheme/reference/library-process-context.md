# (scheme process-context)

Ending the program. The rest of the library (command line, environment variables) is not implemented.

| Name | Example | Result |
|---|---|---|
| `exit` | `(exit 3)` | runs pending `dynamic-wind` afters, then ends with status 3 |
| `emergency-exit` | `(emergency-exit 4)` | ends with status 4, skipping `dynamic-wind` afters |
