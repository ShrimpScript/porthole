package server

import (
	"crypto/rand"
	"crypto/subtle"
	"fmt"
	"math/big"
	"sync"
	"time"
)

// PairCodeTTL is short on purpose: the code is read off the desktop terminal and typed
// straight into the phone, so a long window only widens the guessing envelope.
const PairCodeTTL = 5 * time.Minute

// maxPairAttempts bounds online guessing of a 6-digit code. 10 attempts against a
// 1-in-a-million space, inside a 5 minute window, from a device already on the tailnet.
const maxPairAttempts = 10

type pairCode struct {
	code     string
	expires  time.Time
	attempts int
}

type pairing struct {
	mu      sync.Mutex
	pending *pairCode
}

func newCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

// Begin issues a fresh code, replacing any outstanding one.
func (p *pairing) Begin() (string, time.Time, error) {
	c, err := newCode()
	if err != nil {
		return "", time.Time{}, err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	exp := time.Now().Add(PairCodeTTL)
	p.pending = &pairCode{code: c, expires: exp}
	return c, exp, nil
}

// Redeem consumes the pending code. A correct code is single-use; a wrong one burns an
// attempt. Comparison is constant-time so a timing side channel cannot leak digits.
func (p *pairing) Redeem(given string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	pc := p.pending
	if pc == nil || time.Now().After(pc.expires) {
		p.pending = nil
		return false
	}
	if pc.attempts >= maxPairAttempts {
		p.pending = nil
		return false
	}
	pc.attempts++
	if subtle.ConstantTimeCompare([]byte(pc.code), []byte(given)) == 1 {
		p.pending = nil
		return true
	}
	return false
}

func (p *pairing) Pending() (string, time.Time, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pending == nil || time.Now().After(p.pending.expires) {
		return "", time.Time{}, false
	}
	return p.pending.code, p.pending.expires, true
}
