#!/bin/sh
set -e
getent group sockscope >/dev/null || groupadd --system sockscope
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload
    systemctl enable --now sockscope-collector.service
fi
