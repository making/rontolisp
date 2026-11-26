#!/bin/sh
# stage.sh DIR: the directory Hard.java runs in
d=$1; mkdir -p $d/d $d/ro; cd $d
printf hello > a.txt; printf secret > locked.txt; chmod 000 locked.txt
ln -s a.txt sl; ln -s nothing dang; chmod 555 ro
mkdir -p lockd && chmod 000 lockd
