package server

import (
	"context"
	"errors"
	"net"
	"syscall"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/sshkeys"
	"github.com/shrimpscript/porthole/daemon/internal/tailnet"
)

// failsafe is how the phone's failsafe shell would sign in: "tailscale" when Tailscale
// SSH serves this machine, "key" when the phone's own key is in authorized_keys, "" when
// neither. Tailscale SSH wins because it needs nothing kept in step.
func (s *Server) failsafe(ctx context.Context, nodeID string) string {
	if ts, ok := s.res.(interface {
		RunSSH(context.Context) (bool, error)
	}); ok {
		ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		on, err := ts.RunSSH(ctx)
		cancel()
		if err == nil && on {
			return "tailscale"
		}
	}
	if sshkeys.Has(s.authorizedKeys, nodeID) {
		return "key"
	}
	return ""
}

// sshListening reports whether an SSH server answers here: on a Mac, Remote Login. A
// key in authorized_keys does nothing without one.
func sshListening() bool {
	c, err := net.DialTimeout("tcp", "127.0.0.1:22", 300*time.Millisecond)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

// sshKey adds this phone's public key for the failsafe, or removes it when key is "".
//
// It grants nothing new: a paired phone can already run commands here through Claude
// Code and the terminal. What the key adds is a way in that survives the daemon
// stopping - which is the whole point of a failsafe - so it is tied to this phone's own
// tailnet addresses and goes when the phone is revoked.
func (s *Server) sshKey(ctx context.Context, w *writer, peer *tailnet.Peer, key, deviceName string) {
	path := s.authorizedKeys
	var err error
	if key == "" {
		var removed bool
		if removed, err = sshkeys.Remove(path, peer.NodeID); removed {
			s.log.Info("failsafe key removed", "device", deviceName)
		}
	} else {
		var line string
		if line, err = sshkeys.Line(key, peerAddrs(peer), peer.NodeID); err == nil {
			if err = sshkeys.Install(path, line, peer.NodeID); err == nil {
				s.log.Info("failsafe key added", "device", deviceName, "file", path)
			}
		}
	}
	st := proto.SSHKeyState{
		Frame:     proto.Frame{V: proto.Version, Type: proto.TypeSSHKeyState},
		Failsafe:  s.failsafe(ctx, peer.NodeID),
		SSHServer: sshListening(),
	}
	if err != nil {
		s.log.Warn("failsafe key change failed", "device", deviceName, "err", err)
		st.Error = err.Error()
		if errors.Is(err, syscall.EROFS) {
			// A service unit from before the failsafe key could not write ~/.ssh.
			st.Error = "the daemon's service cannot write ~/.ssh - update it with: portholed service install"
		}
	}
	_ = w.send(ctx, st)
}

// peerAddrs is where the phone connects from: the addresses tailscaled lists for its
// node, or failing that the one this connection came from.
func peerAddrs(p *tailnet.Peer) []string {
	if len(p.Addrs) > 0 {
		return p.Addrs
	}
	if host, _, err := net.SplitHostPort(p.Addr); err == nil {
		return []string{host}
	}
	return nil
}
