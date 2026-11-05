#!/usr/bin/env bash
# The checks of ../sdk-examples/check.sh, on the filters here, with check-errors.sh here.
# Same environment.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
FILTERS_DIR="$here" COMPILE_FLAGS="--no-gc" exec "$here/../sdk-examples/check.sh"
