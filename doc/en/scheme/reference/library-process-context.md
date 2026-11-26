# (scheme process-context)

Ending the program, its command line and its environment variables.

| Name | Example | Result |
|---|---|---|
| `exit` | `(exit 3)` | runs pending `dynamic-wind` afters, then ends with status 3 |
| `emergency-exit` | `(emergency-exit 4)` | ends with status 4, skipping `dynamic-wind` afters |
| `command-line` | `(command-line)` | the program's name, then its arguments, as a list of strings |
| `get-environment-variable` | `(get-environment-variable "RONTOLISP_UNSET")` | the variable's value as a string, or `#f` when it is unset |
| `get-environment-variables` | `(get-environment-variables)` | every variable as a list of `(name . value)` pairs of strings |
