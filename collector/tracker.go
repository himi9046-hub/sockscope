package main

import (
	"sort"
	"strconv"
	"time"
)

type counters struct {
	Tx, Rx uint64
	Cgroup uint64
	Comm   string
}

type proc struct {
	PID     uint32 `json:"pid"`
	Comm    string `json:"comm"`
	Cgroup  uint64 `json:"cgroup"`
	Tx      uint64 `json:"tx"`
	Rx      uint64 `json:"rx"`
	TxTotal uint64 `json:"txTotal"`
	RxTotal uint64 `json:"rxTotal"`
}

type sample struct {
	Time  int64  `json:"t"`
	Procs []proc `json:"procs"`
}

type tracker struct {
	last map[uint32]counters
}

func newTracker() *tracker {
	return &tracker{last: map[uint32]counters{}}
}

func (t *tracker) step(now time.Time, current map[uint32]counters, alive func(uint32) bool) (sample, []uint32) {
	s := sample{Time: now.Unix(), Procs: []proc{}}
	var gone []uint32

	for pid, c := range current {
		prev := t.last[pid]
		t.last[pid] = c
		tx, rx := since(prev.Tx, c.Tx), since(prev.Rx, c.Rx)
		if tx > 0 || rx > 0 {
			s.Procs = append(s.Procs, proc{PID: pid, Comm: c.Comm, Cgroup: c.Cgroup, Tx: tx, Rx: rx, TxTotal: c.Tx, RxTotal: c.Rx})
		}
		if !alive(pid) {
			gone = append(gone, pid)
			delete(t.last, pid)
		}
	}
	for pid := range t.last {
		if _, ok := current[pid]; !ok {
			delete(t.last, pid)
		}
	}

	sort.Slice(s.Procs, func(i, j int) bool {
		a, b := s.Procs[i], s.Procs[j]
		if a.Tx+a.Rx != b.Tx+b.Rx {
			return a.Tx+a.Rx > b.Tx+b.Rx
		}
		return a.PID < b.PID
	})
	sort.Slice(gone, func(i, j int) bool { return gone[i] < gone[j] })
	return s, gone
}

func since(prev, cur uint64) uint64 {
	if cur < prev {
		return cur
	}
	return cur - prev
}

func cstr[T ~int8 | ~uint8](b []T) string {
	out := make([]byte, 0, len(b))
	for _, c := range b {
		if c == 0 {
			break
		}
		out = append(out, byte(c))
	}
	return string(out)
}

func itoa(pid uint32) string {
	return strconv.FormatUint(uint64(pid), 10)
}
