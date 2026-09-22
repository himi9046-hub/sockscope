package main

import (
	"encoding/json"
	"net"
	"sync"
	"time"
)

type hub struct {
	mu      sync.Mutex
	clients map[net.Conn]struct{}
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
		h.mu.Unlock()
	}
}

func (h *hub) send(s sample) error {
	line, err := json.Marshal(s)
	if err != nil {
		return err
	}
	line = append(line, '\n')

	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients {
		c.SetWriteDeadline(time.Now().Add(500 * time.Millisecond))
		if _, err := c.Write(line); err != nil {
			c.Close()
			delete(h.clients, c)
		}
	}
	return nil
}

func (h *hub) count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.clients)
}
