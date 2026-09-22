package main

import (
	"net/netip"
	"time"
)

const (
	afInet  = 2
	afInet6 = 10
)

type rawConn struct {
	Kind       uint8
	Dir        uint8
	Pid        uint32
	Comm       string
	Cgroup     uint64
	Family     uint16
	Saddr      [16]byte
	Daddr      [16]byte
	Sport      uint16
	Dport      uint16
	DurationNs uint64
	Rx, Tx     uint64
}

type connInfo struct {
	Kind   string `json:"kind"`
	Dir    string `json:"dir"`
	PID    uint32 `json:"pid"`
	Comm   string `json:"comm"`
	Cgroup uint64 `json:"cgroup"`
	Local  string `json:"local"`
	LPort  uint16 `json:"lport"`
	Remote string `json:"remote"`
	RPort  uint16 `json:"rport"`
	Ms     uint64 `json:"ms"`
	Rx     uint64 `json:"rx"`
	Tx     uint64 `json:"tx"`
}

type connMessage struct {
	Time int64    `json:"t"`
	Conn connInfo `json:"conn"`
}

func describe(c rawConn, now time.Time) connMessage {
	kind := "open"
	if c.Kind == 2 {
		kind = "close"
	}
	dir := "out"
	if c.Dir == 2 {
		dir = "in"
	}
	return connMessage{
		Time: now.Unix(),
		Conn: connInfo{
			Kind:   kind,
			Dir:    dir,
			PID:    c.Pid,
			Comm:   c.Comm,
			Cgroup: c.Cgroup,
			Local:  addr(c.Family, c.Saddr),
			LPort:  c.Sport,
			Remote: addr(c.Family, c.Daddr),
			RPort:  c.Dport,
			Ms:     c.DurationNs / uint64(time.Millisecond),
			Rx:     c.Rx,
			Tx:     c.Tx,
		},
	}
}

func addr(family uint16, b [16]byte) string {
	if family == afInet {
		return netip.AddrFrom4([4]byte(b[:4])).String()
	}
	a := netip.AddrFrom16(b)
	if a.Is4In6() {
		return a.Unmap().String()
	}
	return a.String()
}
