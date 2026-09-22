package main

import (
	"encoding/json"
	"net"
	"sync"
	"time"
)

const replayLimit = 4096

type hub struct {
	mu      sync.Mutex
	clients map[net.Conn]struct{}
	replay  [][]byte
}

func newHub() *hub {
	return &hub{clients: map[net.Conn]struct{}{}}
}

func (h *hub) serve(l net.Listener) {
	for {
		c, err := l.Accept()
		if err != nil {
			return
		}
		h.mu.Lock()
		h.clients[c] = struct{}{}
		for _, line := range h.replay {
			h.write(c, line)
		}
		h.mu.Unlock()
	}
}

func (h *hub) send(v any) error {
	line, err := json.Marshal(v)
	if err != nil {
		return err
	}
	line = append(line, '\n')

	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := v.(dnsMessage); ok {
		if len(h.replay) == replayLimit {
			h.replay = h.replay[1:]
		}
		h.replay = append(h.replay, line)
	}
	for c := range h.clients {
		h.write(c, line)
	}
	return nil
}

func (h *hub) write(c net.Conn, line []byte) {
	c.SetWriteDeadline(time.Now().Add(500 * time.Millisecond))
	if _, err := c.Write(line); err != nil {
		c.Close()
		delete(h.clients, c)
	}
}

func (h *hub) count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.clients)
}
