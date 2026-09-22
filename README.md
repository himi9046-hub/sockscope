# sockscope

Live network traffic per process on Linux, counted in the kernel with eBPF.

A small collector attaches to the kernel's TCP and UDP send and receive paths
and adds up bytes per process. A JavaFX window shows who is downloading and
uploading right now, with totals.

## How it works

- kprobes on `tcp_sendmsg`, `tcp_cleanup_rbuf`, `udp_sendmsg`, `udp_recvmsg`
  and their IPv6 versions count bytes per process in a BPF hash map.
- Only counters and process metadata are collected. Packet contents are never
  read.
- The collector reads the map once a second and publishes one JSON line per
  sample, either to stdout or to a Unix socket.
- The viewer is a separate process and runs as a normal user. Only the
  collector needs privileges.

```json
{"t":1790087939,"procs":[{"pid":8100,"comm":"curl","cgroup":21,"tx":83,"rx":2000205,"txTotal":83,"rxTotal":2000205}]}
```

`tx` and `rx` are bytes in the last interval, the totals are since the
collector started. `cgroup` is the cgroup id, which tells containers apart.

## Requirements

- Linux 5.8 or newer with BTF (`/sys/kernel/btf/vmlinux` exists). WSL2 works.
- Root, or `CAP_BPF` and `CAP_PERFMON`, for the collector.
- clang, libbpf headers, bpftool and Go to build the collector.
- Java 21 for the viewer.

On Ubuntu:

```
sudo apt install clang llvm libbpf-dev bpftool golang-go openjdk-21-jdk
```

## Build

```
cd collector
go generate ./...
go build -o sockscope-collector .

cd ../app
./gradlew installDist
```

## Run

Print samples to the terminal:

```
sudo ./collector/sockscope-collector
```

Or serve them on a socket and open the viewer:

```
sudo ./collector/sockscope-collector -socket /run/sockscope.sock -group "$(id -gn)" &
app/build/install/sockscope/bin/sockscope
```

The socket is readable by root and the given group. The viewer looks for
`/run/sockscope.sock` unless `SOCKSCOPE_SOCKET` points somewhere else, and
reconnects if the collector restarts.

## Next

- Connection view: open and closed connections with remote host, port and
  duration, from the `sock:inet_sock_set_state` tracepoint.
- TCP retransmits per process.
- Container and pod names instead of cgroup ids.
- `.deb` and `.rpm` packages with a systemd unit for the collector.

## License

MIT. The eBPF program is dual BSD/GPL, which the kernel requires for the
helpers it uses.
