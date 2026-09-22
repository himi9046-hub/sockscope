package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCloseEventCarriesAddressesDurationAndBytes(t *testing.T) {
	c := rawConn{Kind: 2, Pid: 42, Comm: "curl", Family: afInet, Sport: 51000, Dport: 443, DurationNs: 1_234_000_000, Rx: 5000, Tx: 300}
	copy(c.Saddr[:], []byte{192, 168, 1, 5})
	copy(c.Daddr[:], []byte{93, 184, 216, 34})

	m := describe(c, t0)

	want := connInfo{Kind: "close", Dir: "out", PID: 42, Comm: "curl", Local: "192.168.1.5", LPort: 51000, Remote: "93.184.216.34", RPort: 443, Ms: 1234, Rx: 5000, Tx: 300}
	if m.Conn != want {
		t.Fatalf("got %+v", m.Conn)
	}
	if m.Time != t0.Unix() {
		t.Fatalf("time = %d", m.Time)
	}
}

func TestOpenAndDirection(t *testing.T) {
	out := describe(rawConn{Kind: 1, Dir: 1, Family: afInet}, t0).Conn
	in := describe(rawConn{Kind: 1, Dir: 2, Family: afInet}, t0).Conn
	if out.Kind != "open" || out.Dir != "out" || in.Dir != "in" {
		t.Fatalf("out = %+v, in = %+v", out, in)
	}
}

func TestIPv6AndMappedIPv4(t *testing.T) {
	var v6 [16]byte
	copy(v6[:], []byte{0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11})
	if got := addr(afInet6, v6); got != "2606:4700::1111" {
		t.Fatalf("v6 = %s", got)
	}

	var mapped [16]byte
	copy(mapped[:], []byte{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 10, 0, 0, 7})
	if got := addr(afInet6, mapped); got != "10.0.0.7" {
		t.Fatalf("mapped = %s", got)
	}
}

func TestConnMessageShape(t *testing.T) {
	b, err := json.Marshal(describe(rawConn{Kind: 2, Dir: 1, Family: afInet, Comm: "ssh", Dport: 22}, t0))
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{`"t":`, `"conn":{`, `"kind":"close"`, `"remote":"0.0.0.0"`, `"rport":22`, `"ms":0`} {
		if !strings.Contains(string(b), key) {
			t.Fatalf("%s missing %s", b, key)
		}
	}
}
