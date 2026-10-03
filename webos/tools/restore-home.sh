#!/bin/sh
set -eu
hook=/var/lib/webosbrew/init.d/80-mcm-home
if [ -f "$hook" ]; then mv "$hook" /var/lib/mcm-home/80-mcm-home.disabled; fi
if [ -f /var/lib/mcm-home/mapper.pid ]; then
    mapper_pid=$(cat /var/lib/mcm-home/mapper.pid)
    case "$mapper_pid" in ''|*[!0-9]*) exit 1;; esac
    if [ -r "/proc/$mapper_pid/cmdline" ] && tr '\000' ' ' < "/proc/$mapper_pid/cmdline" | grep -q '/var/lib/mcm-home/mapper/runtime/ir-mapper.py'; then
        kill "$mapper_pid"
    fi
fi
echo 'LG Home button restored. MCM remains installed.'
