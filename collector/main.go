package main

//go:generate sh -c "${BPFTOOL:-bpftool} btf dump file /sys/kernel/btf/vmlinux format c > bpf/vmlinux.h"
//go:generate go run github.com/cilium/ebpf/cmd/bpf2go -target amd64 -type conn traffic bpf/traffic.bpf.c

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"flag"
	"log"
	"net"
	"os"
	"os/signal"
	"os/user"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/ringbuf"
	"github.com/cilium/ebpf/rlimit"
)

func main() {
	interval := flag.Duration("interval", time.Second, "how often to report")
	socket := flag.String("socket", "", "serve samples on this unix socket instead of printing them")
	group := flag.String("group", "", "group allowed to read the socket")
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
		{"inet_csk_accept", objs.InetCskAccept, true},
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

	tp, err := link.Tracepoint("sock", "inet_sock_set_state", objs.SockState, nil)
	if err != nil {
		log.Fatalf("attach inet_sock_set_state: %v", err)
	}
	defer tp.Close()

	send := json.NewEncoder(os.Stdout).Encode
	if *socket != "" {
		h, err := listen(*socket, *group)
		if err != nil {
			log.Fatalf("socket: %v", err)
		}
		defer os.Remove(*socket)
		send = h.send
	}
	var mu sync.Mutex
	publish := func(v any) error {
		mu.Lock()
		defer mu.Unlock()
		return send(v)
	}

	events, err := ringbuf.NewReader(objs.Conns)
	if err != nil {
		log.Fatalf("ring buffer: %v", err)
	}
	defer events.Close()
	go readConns(events, publish)

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
			if err := publish(s); err != nil {
				log.Fatalf("write: %v", err)
			}
		}
	}
}

func readConns(r *ringbuf.Reader, publish func(any) error) {
	var c trafficConn
	for {
		rec, err := r.Read()
		if err != nil {
			return
		}
		if err := binary.Read(bytes.NewReader(rec.RawSample), binary.LittleEndian, &c); err != nil {
			log.Printf("bad conn event: %v", err)
			continue
		}
		raw := rawConn{
			Kind: c.Kind, Dir: c.Dir, Pid: c.Pid, Comm: cstr(c.Comm[:]), Cgroup: c.Cgroup, Family: c.Family,
			Saddr: c.Saddr, Daddr: c.Daddr, Sport: c.Sport, Dport: c.Dport,
			DurationNs: c.DurationNs, Rx: c.Rx, Tx: c.Tx,
		}
		if err := publish(describe(raw, time.Now())); err != nil {
			log.Printf("write: %v", err)
		}
	}
}

func listen(path, group string) (*hub, error) {
	os.Remove(path)
	l, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o660); err != nil {
		return nil, err
	}
	if group != "" {
		g, err := user.LookupGroup(group)
		if err != nil {
			return nil, err
		}
		gid, _ := strconv.Atoi(g.Gid)
		if err := os.Chown(path, -1, gid); err != nil {
			return nil, err
		}
	}
	h := newHub()
	go h.serve(l)
	return h, nil
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
