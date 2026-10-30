#!/bin/bash
# Runs the locally extracted emacs-nox with its own data and lisp directories.
ER=@WORK@/emacs-root
export LD_LIBRARY_PATH=$ER/usr/lib/x86_64-linux-gnu
export EMACSLOADPATH=$ER/usr/share/emacs/29.3/lisp:
export EMACSDATA=$ER/usr/share/emacs/29.3/etc
export EMACSDOC=$ER/usr/share/emacs/29.3/etc
exec "$ER/usr/bin/emacs-nox" "$@"
