package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/netip"
	"strings"
	"time"
)

const resolvedMonitor = "/run/systemd/resolve/io.systemd.Resolve.Monitor"

type dnsInfo struct {
	Name  string   `json:"name"`
	Addrs []string `json:"addrs"`
}

type dnsMessage struct {
	Time int64   `json:"t"`
	DNS  dnsInfo `json:"dns"`
}

type record struct {
	RR *struct {
		Key struct {
			Type int    `json:"type"`
			Name string `json:"name"`
		} `json:"key"`
		Address []int `json:"address"`
	} `json:"rr"`
}

func (r record) addr() (string, bool) {
	if r.RR == nil || (r.RR.Key.Type != 1 && r.RR.Key.Type != 28) {
		return "", false
	}
	b := make([]byte, len(r.RR.Address))
	for i, v := range r.RR.Address {
		b[i] = byte(v)
	}
	ip, ok := netip.AddrFromSlice(b)
	if !ok {
		return "", false
	}
	return ip.Unmap().String(), true
}

type monitorReply struct {
	Parameters struct {
		State    string `json:"state"`
		Question []struct {
			Name string `json:"name"`
		} `json:"question"`
		Answer []record `json:"answer"`
	} `json:"parameters"`
}

type cacheReply struct {
	Parameters struct {
		Dump []struct {
			Cache []struct {
				Key struct {
					Name string `json:"name"`
				} `json:"key"`
				RRs []record `json:"rrs"`
			} `json:"cache"`
		} `json:"dump"`
	} `json:"parameters"`
}

func parseMonitor(line []byte) (dnsInfo, bool) {
	var r monitorReply
	if err := json.Unmarshal(line, &r); err != nil {
		return dnsInfo{}, false
	}
	p := r.Parameters
	if p.State != "success" || len(p.Question) == 0 {
		return dnsInfo{}, false
	}

	info := dnsInfo{Name: strings.TrimSuffix(p.Question[0].Name, ".")}
	for _, a := range p.Answer {
		if ip, ok := a.addr(); ok {
			info.Addrs = append(info.Addrs, ip)
		}
	}
	return info, len(info.Addrs) > 0
}

func parseCache(line []byte) []dnsInfo {
	var r cacheReply
	if err := json.Unmarshal(line, &r); err != nil {
		return nil
	}
	var out []dnsInfo
	for _, scope := range r.Parameters.Dump {
		for _, entry := range scope.Cache {
			info := dnsInfo{Name: strings.TrimSuffix(entry.Key.Name, ".")}
			for _, rr := range entry.RRs {
				if ip, ok := rr.addr(); ok {
					info.Addrs = append(info.Addrs, ip)
				}
			}
			if len(info.Addrs) > 0 {
				out = append(out, info)
			}
		}
	}
	return out
}

func watchDNS(path string, publish func(any) error) {
	warned := false
	for {
		err := subscribe(path, publish)
		if !warned {
			log.Printf("dns names unavailable (%v), retrying quietly", err)
			warned = true
		}
		time.Sleep(10 * time.Second)
	}
}

func subscribe(path string, publish func(any) error) error {
	c, err := net.Dial("unix", path)
	if err != nil {
		return err
	}
	defer c.Close()

	r := bufio.NewReader(c)
	if _, err := c.Write([]byte(`{"method":"io.systemd.Resolve.Monitor.DumpCache","parameters":{}}` + "\x00")); err != nil {
		return err
	}
	dump, err := r.ReadBytes(0)
	if err != nil {
		return err
	}
	now := time.Now().Unix()
	for _, info := range parseCache(dump[:len(dump)-1]) {
		if err := publish(dnsMessage{Time: now, DNS: info}); err != nil {
			log.Printf("write: %v", err)
		}
	}

	call := `{"method":"io.systemd.Resolve.Monitor.SubscribeQueryResults","parameters":{},"more":true}` + "\x00"
	if _, err := c.Write([]byte(call)); err != nil {
		return err
	}

	for {
		msg, err := r.ReadBytes(0)
		if err != nil {
			return err
		}
		msg = msg[:len(msg)-1]
		var head struct {
			Error string `json:"error"`
		}
		if json.Unmarshal(msg, &head) == nil && head.Error != "" {
			return errors.New(head.Error)
		}
		if info, ok := parseMonitor(msg); ok {
			if err := publish(dnsMessage{Time: time.Now().Unix(), DNS: info}); err != nil {
				log.Printf("write: %v", err)
			}
		}
	}
}
