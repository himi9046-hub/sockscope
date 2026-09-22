package main

import (
	"bufio"
	"net"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
)

const githubReply = `{"parameters":{"state":"success","question":[{"class":1,"type":1,"name":"github.com"},{"class":1,"type":28,"name":"github.com"}],"answer":[{"rr":{"key":{"class":1,"type":1,"name":"github.com"},"address":[140,82,121,4]},"raw":"BmdpdGh1YgNjb20AAAEAAQAAABcABIxSeQQ="},{"rr":{"key":{"class":1,"type":6,"name":"github.com"},"mname":"ns-1707.awsdns-21.co.uk","rname":"awsdns-hostmaster.amazon.com","serial":1},"raw":"AAAA"}]},"continues":true}`

func TestParsesAddressesFromAnAnswer(t *testing.T) {
	info, ok := parseMonitor([]byte(githubReply))
	if !ok {
		t.Fatal("not parsed")
	}
	want := dnsInfo{Name: "github.com", Addrs: []string{"140.82.121.4"}}
	if !reflect.DeepEqual(info, want) {
		t.Fatalf("got %+v", info)
	}
}

func TestNamesTheQuestionNotTheCanonicalTarget(t *testing.T) {
	reply := `{"parameters":{"state":"success","question":[{"type":28,"name":"www.example.com."}],"answer":[
		{"rr":{"key":{"type":5,"name":"www.example.com"},"name":"edge.cdn.net"}},
		{"rr":{"key":{"type":28,"name":"edge.cdn.net"},"address":[38,6,71,0,0,0,0,0,0,0,0,0,0,0,17,17]}}]}}`
	info, ok := parseMonitor([]byte(reply))
	if !ok || info.Name != "www.example.com" || info.Addrs[0] != "2606:4700::1111" {
		t.Fatalf("got %+v %v", info, ok)
	}
}

const cacheDump = `{"parameters":{"dump":[{"protocol":"dns","cache":[{"key":{"class":1,"type":1,"name":"localtest.me"},"rrs":[{"rr":{"key":{"class":1,"type":1,"name":"localtest.me"},"address":[127,0,0,1]},"raw":"AA=="}],"until":1},{"key":{"class":1,"type":5,"name":"www.example.com"},"rrs":[{"rr":{"key":{"class":1,"type":5,"name":"www.example.com"},"name":"edge.cdn.net"},"raw":"AA=="}],"until":1},{"key":{"class":1,"type":28,"name":"example.net"},"rrs":[{"rr":{"key":{"class":1,"type":28,"name":"example.net"},"address":[42,6,152,193,49,35,128,0,0,0,0,0,0,0,0,0]},"raw":"AA=="},{"rr":{"key":{"class":1,"type":28,"name":"example.net"},"address":[42,6,152,193,49,34,128,0,0,0,0,0,0,0,0,0]},"raw":"AA=="}],"until":1}],"dnssec":"no"}]}}`

func TestReadsNamesFromTheCacheDump(t *testing.T) {
	got := parseCache([]byte(cacheDump))
	want := []dnsInfo{
		{Name: "localtest.me", Addrs: []string{"127.0.0.1"}},
		{Name: "example.net", Addrs: []string{"2a06:98c1:3123:8000::", "2a06:98c1:3122:8000::"}},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v", got)
	}
}

func TestIgnoresFailuresAndEmptyAnswers(t *testing.T) {
	for _, reply := range []string{
		`{"ready":true}`,
		`{"parameters":{"ready":true},"continues":true}`,
		`{"parameters":{"state":"rcode-failure","question":[{"type":1,"name":"nope.invalid"}]}}`,
		`{"parameters":{"state":"success","question":[{"type":1,"name":"x"}],"answer":[]}}`,
		`not json`,
	} {
		if info, ok := parseMonitor([]byte(reply)); ok {
			t.Fatalf("%s gave %+v", reply, info)
		}
	}
}

func TestSubscribesAndPublishesNames(t *testing.T) {
	path := filepath.Join(t.TempDir(), "monitor.sock")
	l, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()

	calls := make(chan string, 1)
	go func() {
		c, err := l.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		r := bufio.NewReader(c)
		dump, _ := r.ReadString(0)
		c.Write([]byte(cacheDump + "\x00"))
		call, _ := r.ReadString(0)
		calls <- dump + call
		c.Write([]byte(`{"parameters":{"ready":true},"continues":true}` + "\x00" + githubReply + "\x00"))
	}()

	var mu sync.Mutex
	var got []dnsMessage
	err = subscribe(path, func(v any) error {
		mu.Lock()
		defer mu.Unlock()
		got = append(got, v.(dnsMessage))
		return nil
	})
	if err == nil {
		t.Fatal("subscribe should return when the server hangs up")
	}

	call := <-calls
	for _, want := range []string{`"method":"io.systemd.Resolve.Monitor.DumpCache"`, `"method":"io.systemd.Resolve.Monitor.SubscribeQueryResults"`, `"more":true`} {
		if !strings.Contains(call, want) {
			t.Fatalf("calls %q lack %s", call, want)
		}
	}
	var names []string
	for _, m := range got {
		names = append(names, m.DNS.Name)
	}
	if !reflect.DeepEqual(names, []string{"localtest.me", "example.net", "github.com"}) {
		t.Fatalf("published %v", names)
	}
}

