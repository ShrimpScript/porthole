// Package store persists the device allowlist.
//
// Tailnet membership alone does not grant access: the world-writable tailscaled socket
// and the fact that a tailnet can hold devices you did not intend to trust mean identity
// must be confirmed once, out of band, by a code shown on this machine.
package store

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

type Device struct {
	NodeID   string    `json:"node_id"` // tailnet StableID - survives IP changes
	Name     string    `json:"name"`
	User     string    `json:"user"`
	PairedAt time.Time `json:"paired_at"`
	LastSeen time.Time `json:"last_seen,omitempty"`
	Scopes   []string  `json:"scopes"`
}

type Store struct {
	mu      sync.RWMutex
	path    string
	devices map[string]*Device
}

func defaultDir() string {
	if d := os.Getenv("PORTHOLE_STATE_DIR"); d != "" {
		return d
	}
	if d := os.Getenv("XDG_CONFIG_HOME"); d != "" {
		return filepath.Join(d, "porthole")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "porthole")
}

func Open(path string) (*Store, error) {
	if path == "" {
		path = filepath.Join(defaultDir(), "devices.json")
	}
	s := &Store{path: path, devices: map[string]*Device{}}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, err
	}
	var list []*Device
	if err := json.Unmarshal(b, &list); err != nil {
		return nil, fmt.Errorf("device store is corrupt (%s): %w", path, err)
	}
	for _, d := range list {
		s.devices[d.NodeID] = d
	}
	return s, nil
}

// save assumes the caller holds the write lock.
func (s *Store) save() error {
	list := make([]*Device, 0, len(s.devices))
	for _, d := range s.devices {
		list = append(list, d)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].PairedAt.Before(list[j].PairedAt) })
	b, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	// Write-then-rename so a crash cannot leave a half-written allowlist, which would
	// otherwise fail open on the next start.
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) Allowed(nodeID string) (*Device, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	d, ok := s.devices[nodeID]
	return d, ok
}

func (s *Store) Add(d *Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if d.PairedAt.IsZero() {
		d.PairedAt = time.Now()
	}
	s.devices[d.NodeID] = d
	return s.save()
}

func (s *Store) Touch(nodeID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if d, ok := s.devices[nodeID]; ok {
		d.LastSeen = time.Now()
		_ = s.save()
	}
}

// Revoke removes a device. Returns false if it was not paired.
func (s *Store) Revoke(nodeID string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.devices[nodeID]; !ok {
		return false, nil
	}
	delete(s.devices, nodeID)
	return true, s.save()
}

func (s *Store) List() []*Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	list := make([]*Device, 0, len(s.devices))
	for _, d := range s.devices {
		cp := *d
		list = append(list, &cp)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].PairedAt.Before(list[j].PairedAt) })
	return list
}
