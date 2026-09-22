package main

import (
	"testing"
	"time"
)

var t0 = time.Unix(1758520000, 0)

func always(uint32) bool { return true }

func TestFirstSampleReportsWhatWasCountedSoFar(t *testing.T) {
	tr := newTracker()
	s, gone := tr.step(t0, map[uint32]counters{
		10: {Tx: 500, Rx: 2000, Comm: "curl"},
		20: {Tx: 0, Rx: 0, Comm: "idle"},
	}, always)

	if len(gone) != 0 {
		t.Fatalf("gone = %v", gone)
	}
	if len(s.Procs) != 1 || s.Procs[0].PID != 10 || s.Procs[0].Tx != 500 || s.Procs[0].Rx != 2000 {
		t.Fatalf("procs = %+v", s.Procs)
	}
	if s.Time != t0.Unix() {
		t.Fatalf("time = %d", s.Time)
	}
}

func TestLaterSamplesReportDeltasAndKeepTotals(t *testing.T) {
	tr := newTracker()
	tr.step(t0, map[uint32]counters{10: {Tx: 500, Rx: 2000, Comm: "curl"}}, always)
	s, _ := tr.step(t0.Add(time.Second), map[uint32]counters{10: {Tx: 800, Rx: 2000, Comm: "curl"}}, always)

	p := s.Procs[0]
	if p.Tx != 300 || p.Rx != 0 || p.TxTotal != 800 || p.RxTotal != 2000 {
		t.Fatalf("proc = %+v", p)
	}
}

func TestQuietProcessesAreLeftOut(t *testing.T) {
	tr := newTracker()
	tr.step(t0, map[uint32]counters{10: {Tx: 5}}, always)
	s, _ := tr.step(t0.Add(time.Second), map[uint32]counters{10: {Tx: 5}}, always)

	if len(s.Procs) != 0 {
		t.Fatalf("procs = %+v", s.Procs)
	}
}

func TestBusiestFirst(t *testing.T) {
	tr := newTracker()
	s, _ := tr.step(t0, map[uint32]counters{
		1: {Tx: 10},
		2: {Rx: 1000},
		3: {Tx: 10},
	}, always)

	got := [3]uint32{s.Procs[0].PID, s.Procs[1].PID, s.Procs[2].PID}
	if got != [3]uint32{2, 1, 3} {
		t.Fatalf("order = %v", got)
	}
}

func TestExitedProcessesAreReportedOnceThenDropped(t *testing.T) {
	tr := newTracker()
	dead := func(pid uint32) bool { return pid != 10 }

	s, gone := tr.step(t0, map[uint32]counters{10: {Tx: 100}, 11: {Tx: 1}}, dead)
	if len(s.Procs) != 2 {
		t.Fatalf("last traffic of an exited process should still be reported: %+v", s.Procs)
	}
	if len(gone) != 1 || gone[0] != 10 {
		t.Fatalf("gone = %v", gone)
	}
	if _, ok := tr.last[10]; ok {
		t.Fatal("exited process is still tracked")
	}
}

func TestReusedPidStartsFromZero(t *testing.T) {
	tr := newTracker()
	tr.step(t0, map[uint32]counters{10: {Tx: 5000}}, always)
	s, _ := tr.step(t0.Add(time.Second), map[uint32]counters{10: {Tx: 40}}, always)

	if s.Procs[0].Tx != 40 {
		t.Fatalf("tx = %d", s.Procs[0].Tx)
	}
}

func TestCommStopsAtNul(t *testing.T) {
	raw := [16]int8{'n', 'g', 'i', 'n', 'x', 0, 'x'}
	if got := cstr(raw[:]); got != "nginx" {
		t.Fatalf("cstr = %q", got)
	}
}
