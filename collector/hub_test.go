package main

import (
	"bufio"
	"encoding/json"
	"net"
	"path/filepath"
	"testing"
	"time"
)

func TestEveryClientGetsEachSample(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	l, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()

	h := newHub()
	go h.serve(l)

	var readers []*bufio.Reader
	for range 2 {
		c, err := net.Dial("unix", path)
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		readers = append(readers, bufio.NewReader(c))
	}
	waitFor(t, func() bool { return h.count() == 2 })

	if err := h.send(sample{Time: 42, Procs: []proc{{PID: 7, Comm: "curl", Rx: 100}}}); err != nil {
		t.Fatal(err)
	}

	for i, r := range readers {
		line, err := r.ReadBytes('\n')
		if err != nil {
			t.Fatalf("client %d: %v", i, err)
		}
		var got sample
		if err := json.Unmarshal(line, &got); err != nil {
			t.Fatalf("client %d: %v", i, err)
		}
		if got.Time != 42 || got.Procs[0].Comm != "curl" || got.Procs[0].Rx != 100 {
			t.Fatalf("client %d got %+v", i, got)
		}
	}
}

func TestDisconnectedClientsAreDropped(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	l, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()

	h := newHub()
	go h.serve(l)

	c, err := net.Dial("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	waitFor(t, func() bool { return h.count() == 1 })
	c.Close()

	waitFor(t, func() bool {
		h.send(sample{Procs: []proc{}})
		return h.count() == 0
	})
}

func TestLateClientsGetKnownHostNames(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	l, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()

	h := newHub()
	go h.serve(l)
	h.send(dnsMessage{Time: 1, DNS: dnsInfo{Name: "github.com", Addrs: []string{"140.82.121.4"}}})
	h.send(sample{Time: 2, Procs: []proc{}})

	c, err := net.Dial("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))

	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	var got dnsMessage
	if err := json.Unmarshal([]byte(line), &got); err != nil || got.DNS.Name != "github.com" {
		t.Fatalf("first line %q (%v)", line, err)
	}
}

func waitFor(t *testing.T, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatal("timed out")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
