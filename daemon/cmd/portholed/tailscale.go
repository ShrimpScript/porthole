package main

import (
	"os"
	"os/exec"
)

// tailscaleCLI is the tailscale command: on the PATH, or - for the Tailscale app on a
// Mac, whose CLI is only on the PATH if it was installed from the app's menu - inside
// the app bundle, where the same binary answers as the CLI.
func tailscaleCLI() string {
	if p, err := exec.LookPath("tailscale"); err == nil {
		return p
	}
	const app = "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
	if _, err := os.Stat(app); err == nil {
		return app
	}
	return "tailscale"
}
