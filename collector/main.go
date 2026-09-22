package main

//go:generate sh -c "bpftool btf dump file /sys/kernel/btf/vmlinux format c > bpf/vmlinux.h"
//go:generate go run github.com/cilium/ebpf/cmd/bpf2go -target amd64 traffic bpf/traffic.bpf.c

import (
	"context"
	"encoding/json"
	"flag"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/rlimit"
)

func main() {
	interval := flag.Duration("interval", time.Second, "how often to report")
	flag.Parse()
	log.SetFlags(0)

	if err := rlimit.RemoveMemlock(); err != nil {
		log.Fatalf("memlock: %v", err)
	}

	spec, err := loadTraffic()
	if err != nil {
		log.Fatalf("load bpf: %v", err)
	}
	var ns syscall.Stat_t
	if err := syscall.Stat("/proc/self/ns/pid", &ns); err != nil {
		log.Fatalf("pid namespace: %v", err)
	}
	if err := spec.Variables["ns_dev"].Set(uint64(ns.Dev)); err != nil {
		log.Fatalf("set ns_dev: %v", err)
	}
	if err := spec.Variables["ns_ino"].Set(ns.Ino); err != nil {
		log.Fatalf("set ns_ino: %v", err)
	}

	var objs trafficObjects
	if err := spec.LoadAndAssign(&objs, nil); err != nil {
		log.Fatalf("load bpf: %v", err)
	}
	defer objs.Close()

	probes := []struct {
		fn   string
		prog *ebpf.Program
		ret  bool
	}{
		{"tcp_sendmsg", objs.TcpSendmsg, false},
		{"tcp_cleanup_rbuf", objs.TcpCleanupRbuf, false},
		{"udp_sendmsg", objs.UdpSendmsg, false},
		{"udpv6_sendmsg", objs.Udpv6Sendmsg, false},
		{"udp_recvmsg", objs.UdpRecvmsg, true},
		{"udpv6_recvmsg", objs.Udpv6Recvmsg, true},
	}
	for _, p := range probes {
		attach := link.Kprobe
		if p.ret {
			attach = link.Kretprobe
		}
		l, err := attach(p.fn, p.prog, nil)
		if err != nil {
			log.Fatalf("attach %s: %v", p.fn, err)
		}
		defer l.Close()
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	out := json.NewEncoder(os.Stdout)
	tracker := newTracker()
	tick := time.NewTicker(*interval)
	defer tick.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case now := <-tick.C:
			current, err := readUsage(objs.Usage)
			if err != nil {
				log.Fatalf("read map: %v", err)
			}
			s, gone := tracker.step(now, current, alive)
			for _, pid := range gone {
				objs.Usage.Delete(pid)
			}
			if err := out.Encode(s); err != nil {
				log.Fatalf("write: %v", err)
			}
		}
	}
}

func readUsage(m *ebpf.Map) (map[uint32]counters, error) {
	result := map[uint32]counters{}
	var pid uint32
	var u trafficUsage
	it := m.Iterate()
	for it.Next(&pid, &u) {
		result[pid] = counters{Tx: u.Tx, Rx: u.Rx, Cgroup: u.Cgroup, Comm: name(pid, cstr(u.Comm[:]))}
	}
	return result, it.Err()
}

func name(pid uint32, fallback string) string {
	b, err := os.ReadFile("/proc/" + itoa(pid) + "/comm")
	if err != nil {
		return fallback
	}
	return strings.TrimSpace(string(b))
}

func alive(pid uint32) bool {
	_, err := os.Stat("/proc/" + itoa(pid))
	return err == nil
}
