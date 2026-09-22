#!/bin/sh
set -e
"${BPFTOOL:-bpftool}" btf dump file /sys/kernel/btf/vmlinux format c > bpf/vmlinux.h
