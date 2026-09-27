// Command portholed is the desktop side of Porthole.
//
//	portholed serve      run the daemon (bound to the tailnet only)
//	portholed pair       print a 6-digit code for a new phone
//	portholed devices    list paired devices
//	portholed revoke ID  remove a device and drop its live sockets
//	portholed status     is it running, how many devices, how many connected
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"github.com/shrimpscript/porthole/daemon/internal/service"
	qrcode "github.com/skip2/go-qrcode"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/hooks"
	"github.com/shrimpscript/porthole/daemon/internal/server"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/store"
	"github.com/shrimpscript/porthole/daemon/internal/tailnet"
	"github.com/shrimpscript/porthole/daemon/internal/termbridge"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

const defaultPort = "8737"

func main() {
	// Called as `porthole` (a link install.sh makes), this is Claude Code started where the
	// phone can reach it; every argument goes to claude.
	if filepath.Base(os.Args[0]) == "porthole" {
		if err := cmdClaude(os.Args[1:]); err != nil {
			fmt.Fprintln(os.Stderr, "porthole: "+err.Error())
			os.Exit(1)
		}
		return
	}
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "claude":
		err = cmdClaude(os.Args[2:])
	case "serve":
		err = cmdServe(os.Args[2:])
	case "pair":
		err = cmdPair(os.Args[2:])
	case "devices":
		err = cmdDevices()
	case "revoke":
		err = cmdRevoke(os.Args[2:])
	case "status":
		err = cmdStatus()
	case "doctor":
		err = cmdDoctor()
	case "sessions":
		err = cmdSessions()
	case "replay":
		err = cmdReplay(os.Args[2:])
	case "hook":
		err = cmdHook()
	case "install-hooks":
		err = cmdInstallHooks()
	case "uninstall-hooks":
		err = cmdUninstallHooks()
	case "publish":
		err = cmdPublish(os.Args[2:])
	case "service":
		err = cmdService(os.Args[2:])
	case "setup":
		err = cmdSetup(os.Args[2:])
	case "version", "--version", "-v":
		fmt.Println("portholed " + server.Version)
		return
	case "-h", "--help", "help":
		usage()
		return
	default:
		fmt.Fprintf(os.Stderr, "portholed: unknown command %q\n\n", os.Args[1])
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "portholed: "+err.Error())
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `portholed - the desktop side of Porthole

  porthole [ARGS]        start Claude Code inside tmux, where the phone can see and type
                         to it (same as portholed claude ARGS; ARGS go to claude)
  portholed serve        run the daemon (binds the tailnet only)
  portholed pair         print a 6-digit code and a QR to pair a phone (-png FILE, -no-qr)
  portholed devices      list paired devices
  portholed revoke ID    remove a device, dropping its live connections
  portholed setup        install the background service and the approval hook, once
                         (-no-hooks skips the hook); what install.sh runs
  portholed status       show daemon status
  portholed version      print the version
  portholed doctor       check everything a phone needs on this computer, one line each
  portholed sessions     list Claude Code sessions on this machine
  portholed replay FILE  map a transcript to feed rows (-json, -feed)
  portholed publish APK VERSION APP  offer a build of an app you are working on to paired phones
  portholed service install|restart|status|uninstall
                         run the daemon in the background: a launchd agent on a Mac,
                         a systemd user service on Linux (this binary's own path)
  portholed install-hooks    register the PermissionRequest hook with Claude Code
  portholed uninstall-hooks  remove it again
  portholed hook             (invoked by Claude Code; not for humans)

The daemon never listens on the LAN. Access requires a tailnet identity AND a device
that has been paired with a code shown on this machine.
`)
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	port := fs.String("port", defaultPort, "port to bind on the tailnet addresses")
	devAddr := fs.String("dev-listen", "", "DEVELOPMENT ONLY: bind this loopback address with a stub identity")
	verbose := fs.Bool("v", false, "debug logging")
	_ = fs.Parse(args)

	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))

	st, err := store.Open("")
	if err != nil {
		return err
	}

	var res tailnet.Resolver
	var listeners []net.Listener
	var bindIPs, bindNames []string
	bindPort := 0

	if *devAddr != "" {
		// Dev mode exists because a host cannot reach its own tailnet IP on the tunnel,
		// so the refusal/pairing paths are otherwise untestable on one machine. It binds
		// loopback and is refused outright on any other address.
		host, _, splitErr := net.SplitHostPort(*devAddr)
		if splitErr != nil {
			return fmt.Errorf("-dev-listen must be host:port")
		}
		if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
			return fmt.Errorf("-dev-listen only accepts a loopback address, got %q", host)
		}
		log.Warn("DEVELOPMENT MODE: stub identity, loopback only", "addr", *devAddr)
		res = stubResolver{}
		bindIPs = []string{host}
		// The emulator reaches the host's loopback as 10.0.2.2; a phone on the LAN cannot
		// reach a loopback bind at all, so these names widen nothing.
		bindNames = []string{"localhost", "10.0.2.2"}
		if _, p, err := net.SplitHostPort(*devAddr); err == nil {
			bindPort, _ = strconv.Atoi(p)
		}
		ln, err := net.Listen("tcp", *devAddr)
		if err != nil {
			return err
		}
		listeners = append(listeners, ln)
	} else {
		tc, err := tailnet.New()
		if err != nil {
			return err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		status, err := tc.Status(ctx)
		cancel()
		if err != nil {
			return fmt.Errorf("could not reach tailscaled: %w", err)
		}
		if !status.Connected {
			return errors.New("tailscale is not connected")
		}
		if len(status.SelfIPs) == 0 {
			return errors.New("this machine has no tailnet address")
		}
		res = tc
		bindIPs = status.SelfIPs
		bindNames = []string{strings.TrimSuffix(status.SelfName, ".")}
		bindPort, _ = strconv.Atoi(*port)
		listeners, err = server.TailnetListeners(status.SelfIPs, *port)
		if err != nil {
			return err
		}
		log.Info("bound tailnet addresses only", "addrs", strings.Join(status.SelfIPs, ","),
			"port", *port, "host", strings.TrimSuffix(status.SelfName, "."))
	}

	srv := server.New(res, st, log)
	srv.SetBind(bindIPs, bindPort, bindNames)
	turnCtx, stopTurns := context.WithCancel(context.Background())
	defer stopTurns()
	srv.StartTurnWatcher(turnCtx)

	ctl, err := server.ListenControl("")
	if err != nil {
		return err
	}
	defer ctl.Close()
	go func() {
		if err := srv.ServeControl(ctl); err != nil {
			log.Debug("control socket closed", "err", err)
		}
	}()
	log.Info("control socket ready", "path", server.ControlSocketPath())

	// A daemon that died with a terminal open leaves its tmux mirror behind and, worse,
	// leaves the user's own window pinned to window-size=largest. Undo that before
	// serving anything.
	reapCtx, reapCancel := context.WithTimeout(context.Background(), 5*time.Second)
	reaped := termbridge.Reap(reapCtx)
	reapCancel()
	if len(reaped) > 0 {
		log.Info("cleaned up mirrors from a previous run", "sessions", strings.Join(reaped, ", "))
	}

	h := &http.Server{
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	for _, ln := range listeners {
		go func(ln net.Listener) {
			if err := h.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Error("listener stopped", "addr", ln.Addr().String(), "err", err)
			}
		}(ln)
		log.Info("listening", "addr", ln.Addr().String())
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
	log.Info("shutting down")
	shutCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return h.Shutdown(shutCtx)
}

// stubResolver names every loopback caller as one synthetic device, for -dev-listen.
type stubResolver struct{}

func (stubResolver) WhoIs(_ context.Context, remoteAddr string) (*tailnet.Peer, error) {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		return nil, err
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return nil, fmt.Errorf("dev resolver refuses non-loopback %s", host)
	}
	return &tailnet.Peer{
		NodeID: "nDEVSTUB000000CNTRL", Name: "dev-stub.example.ts.net.",
		Addr: remoteAddr, UserLogin: "dev@localhost",
	}, nil
}

// cmdPublish copies an APK of an app being worked on into the builds directory. Paired
// phones see the newest build of each app in Hello and offer to install it; a phone can
// also fetch it from the daemon's /builds/ page in a browser.
func cmdPublish(args []string) error {
	if len(args) != 3 {
		return errors.New("usage: portholed publish <apk> <version> <app>   e.g. portholed publish app-release.apk 1.4.0 my-app")
	}
	b, err := server.PublishBuild(args[0], args[1], args[2])
	if err != nil {
		return err
	}
	fmt.Printf("published %s %s as %s (%d bytes) to %s\n", server.DisplayName(b.App), b.Version, b.File, b.Size, server.BuildsDir())
	fmt.Println("paired phones will offer it on their next connection")
	if tc, err := tailnet.New(); err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		if st, err := tc.Status(ctx); err == nil && len(st.SelfIPs) > 0 {
			fmt.Printf("or in the phone's browser: http://%s/builds/\n", net.JoinHostPort(st.SelfIPs[0], defaultPort))
		}
		cancel()
	}
	return nil
}

func cmdPair(args []string) error {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	png := fs.String("png", "", "also write the pairing QR as a PNG to this path")
	noQR := fs.Bool("no-qr", false, "print the code only")
	if err := fs.Parse(args); err != nil {
		return err
	}
	resp, err := server.Call("", map[string]string{"cmd": "pair"})
	if err != nil {
		return err
	}
	mins := int(time.Until(resp.Expires).Round(time.Minute).Minutes())
	fmt.Printf("\n  Pairing code:  %s\n\n", spaced(resp.Code))
	// The same thing as a QR: host and code in one scan, so the phone types nothing.
	// The link is a porthole:// URL the app claims; the system camera opens it too.
	if link := pairLink(resp.Code); link != "" {
		if !*noQR {
			fmt.Print(qrText(link))
			fmt.Println("  Scan with Porthole (Pair > Scan), or with the phone's camera.")
		}
		if *png != "" {
			if err := qrcode.WriteFile(link, qrcode.Medium, 512, *png); err != nil {
				return err
			}
			fmt.Printf("  QR written to %s\n", *png)
		}
	}
	fmt.Printf("  Or type the code into Porthole. Expires in %d minutes.\n\n", mins)
	return nil
}

// pairLink is porthole://pair?host=<tailnet ip>:<port>&code=<code>, or "" when the
// tailnet address is not known (dev mode, tailscaled down): then the code alone is printed.
func pairLink(code string) string {
	tc, err := tailnet.New()
	if err != nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	st, err := tc.Status(ctx)
	if err != nil || len(st.SelfIPs) == 0 {
		return ""
	}
	host := st.SelfIPs[0]
	for _, ip := range st.SelfIPs { // prefer the IPv4 address: shorter QR, and what the app shows
		if !strings.Contains(ip, ":") {
			host = ip
			break
		}
	}
	return "porthole://pair?host=" + net.JoinHostPort(host, defaultPort) + "&code=" + code
}

// qrText renders a QR with half-block characters: two modules per character row, which
// keeps it square in a terminal and small enough to sit beside the code.
func qrText(content string) string {
	q, err := qrcode.New(content, qrcode.Medium)
	if err != nil {
		return ""
	}
	q.DisableBorder = true
	bits := q.Bitmap()
	n := len(bits)
	var b strings.Builder
	quiet := 2
	blank := strings.Repeat("\u2588", n+2*quiet) // full block = "white" on a dark terminal
	b.WriteString("  " + blank + "\n")
	for y := 0; y < n; y += 2 {
		b.WriteString("  " + strings.Repeat("\u2588", quiet))
		for x := 0; x < n; x++ {
			top := bits[y][x]
			bottom := y+1 < n && bits[y+1][x]
			switch {
			case top && bottom:
				b.WriteString(" ")
			case top:
				b.WriteString("\u2584") // lower half block: bottom is light
			case bottom:
				b.WriteString("\u2580") // upper half block: top is light
			default:
				b.WriteString("\u2588")
			}
		}
		b.WriteString(strings.Repeat("\u2588", quiet) + "\n")
	}
	b.WriteString("  " + blank + "\n\n")
	return b.String()
}

// spaced prints 123456 as "123 456" - two groups are markedly easier to carry across
// the room to a phone than six undifferentiated digits.
func spaced(code string) string {
	if len(code) != 6 {
		return code
	}
	return code[:3] + " " + code[3:]
}

func cmdDevices() error {
	resp, err := server.Call("", map[string]string{"cmd": "devices"})
	if err != nil {
		return err
	}
	if len(resp.Devices) == 0 {
		fmt.Println("No paired devices. Run `portholed pair` and enter the code on your phone.")
		return nil
	}
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "NAME\tUSER\tPAIRED\tLAST SEEN\tLIVE\tID")
	for _, d := range resp.Devices {
		last := "-"
		if !d.LastSeen.IsZero() {
			last = humanAgo(d.LastSeen)
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%d\t%s\n",
			d.Name, d.User, humanAgo(d.PairedAt), last, d.Live, d.NodeID)
	}
	return w.Flush()
}

func humanAgo(t time.Time) string {
	d := time.Since(t)
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	default:
		return fmt.Sprintf("%dd ago", int(d.Hours()/24))
	}
}

func cmdRevoke(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: portholed revoke <device-id>   (see `portholed devices`)")
	}
	if _, err := server.Call("", map[string]string{"cmd": "revoke", "node_id": args[0]}); err != nil {
		return err
	}
	fmt.Println("Revoked. Any live connection from that device was dropped.")
	return nil
}

func cmdSessions() error {
	// Works without the daemon: discovery is a filesystem read, and being able to check
	// it independently is what makes "the app shows nothing" diagnosable.
	list, err := session.List()
	if err != nil {
		return err
	}
	if len(list) == 0 {
		fmt.Println("No Claude Code sessions found.")
		return nil
	}
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "STATE\tTITLE\tBRANCH\tLAST\tDIRECTORY")
	for _, s := range list {
		state := "idle"
		if s.Live {
			state = "live"
		} else if s.Tmux {
			state = "shell"
		}
		title := s.Title
		if len(title) > 40 {
			title = title[:39] + "…"
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
			state, title, s.Branch, humanAgo(s.LastActive), s.Cwd)
	}
	return w.Flush()
}

func cmdReplay(args []string) error {
	fs := flag.NewFlagSet("replay", flag.ExitOnError)
	asJSON := fs.Bool("json", false, "emit stats as JSON")
	feed := fs.Bool("feed", false, "print the rendered feed")
	limit := fs.Int("limit", 0, "only the last N rows")

	// Go's flag package stops at the first positional argument, so the natural
	// `replay <file> -feed` would fail. Pull the path out first and parse the rest.
	var path string
	var flags []string
	for _, a := range args {
		if path == "" && !strings.HasPrefix(a, "-") {
			path = a
			continue
		}
		flags = append(flags, a)
	}
	_ = fs.Parse(flags)
	if path == "" {
		return errors.New("usage: portholed replay <transcript.jsonl> [-feed] [-json] [-limit N]")
	}
	res, err := transcript.ParseFile(path)
	if err != nil {
		return err
	}
	if *asJSON {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(res.Stats)
	}
	fmt.Printf("session   : %s\n", res.Meta.SessionID)
	if res.Meta.Title != "" {
		fmt.Printf("title     : %s\n", res.Meta.Title)
	}
	fmt.Printf("cwd       : %s  (%s)\n", res.Meta.Cwd, res.Meta.GitBranch)
	fmt.Printf("records   : %d   rows: %d   silent: %d\n",
		res.Stats.Records, res.Stats.Rows, res.Stats.Silent)
	if res.Stats.Malformed > 0 {
		fmt.Printf("malformed : %d line(s) skipped\n", res.Stats.Malformed)
	}
	if len(res.Stats.Unmapped) > 0 {
		fmt.Printf("UNMAPPED  : %v   <- each one is a row the feed would have to invent\n",
			res.Stats.Unmapped)
	} else {
		fmt.Println("unmapped  : none")
	}
	if !*feed {
		return nil
	}
	rows := res.Rows
	if *limit > 0 && len(rows) > *limit {
		rows = rows[len(rows)-*limit:]
	}
	fmt.Println(strings.Repeat("─", 66))
	for _, r := range rows {
		switch r.Kind {
		case transcript.KindUser:
			fmt.Printf("%18s[ %s ]\n", "", r.Text)
		case transcript.KindQueued:
			fmt.Printf("%18s( %s ) queued\n", "", r.Text)
		case transcript.KindEvent:
			fmt.Printf("  • %s\n", r.Text)
		case transcript.KindAssistant:
			fmt.Printf("  %s\n", r.Text)
		default:
			fmt.Printf("  %s %-38s %18s\n", r.Glyph, r.Text, r.Metric)
		}
	}
	return nil
}

// cmdHook is Claude Code's PermissionRequest handler.
//
// The contract it must never break: ALWAYS exit 0, and never print a decision it did
// not actually receive. Printing nothing leaves the permission flow unchanged, which
// means Claude asks at the desk - the correct outcome when the phone is unreachable,
// the daemon is down, or nobody answered.
func cmdHook() error {
	payload, err := io.ReadAll(io.LimitReader(os.Stdin, 1<<20))
	if err != nil || len(payload) == 0 {
		return nil
	}

	// Generous, because this is waiting on a human with a phone. It still finishes
	// before Claude Code's own hook timeout, so the fallback stays ours to control.
	resp, err := server.CallWithTimeout("", map[string]any{
		"cmd":   "hook",
		"event": json.RawMessage(payload),
	}, server.ApprovalWait+20*time.Second)
	if err != nil || resp == nil || resp.Decision == "" {
		// Daemon down, nobody connected, or nobody answered. Stay silent.
		return nil
	}

	b, ok := server.HookOutput(server.Decision{Decision: resp.Decision, Reason: resp.Reason})
	if !ok {
		return nil
	}
	_, _ = os.Stdout.Write(b)
	return nil
}

func cmdInstallHooks() error {
	// The path that survives upgrades: under Homebrew, opt/ rather than the versioned keg.
	self, err := service.Binary()
	if err != nil {
		return err
	}
	path, err := hooks.Install(self)
	if err != nil {
		return err
	}
	fmt.Printf("Registered the PermissionRequest hook in %s\n", path)
	fmt.Println("Permission prompts will now reach your paired phone.")
	fmt.Println("Remove it any time with: portholed uninstall-hooks")
	return nil
}

func cmdUninstallHooks() error {
	path, removed, err := hooks.Uninstall()
	if err != nil {
		return err
	}
	if !removed {
		fmt.Printf("No Porthole hook found in %s\n", path)
		return nil
	}
	fmt.Printf("Removed Porthole's hook from %s\n", path)
	return nil
}

func cmdStatus() error {
	resp, err := server.Call("", map[string]string{"cmd": "status"})
	if err != nil {
		return err
	}
	fmt.Printf("portholed %s - %d paired device(s), %d connected now\n",
		resp.Status.Version, resp.Status.Devices, resp.Status.Live)
	if installed, path := hooks.Installed(); installed {
		fmt.Printf("permission hook: registered in %s\n", path)
	} else {
		fmt.Println("permission hook: not registered (run `portholed install-hooks`)")
	}
	return nil
}

// cmdSetup is everything after the binary is in place, in one command: the background
// service and the Claude Code hook. A package manager installs the binary; this is the
// line that follows it.
func cmdSetup(args []string) error {
	fs := flag.NewFlagSet("setup", flag.ExitOnError)
	noHooks := fs.Bool("no-hooks", false, "leave ~/.claude/settings.json alone (no approvals from the phone)")
	_ = fs.Parse(args)

	fmt.Println("Background service")
	if err := cmdService([]string{"install"}); err != nil {
		return err
	}
	fmt.Println()
	fmt.Println("Claude Code hook")
	if *noHooks {
		fmt.Println("skipped: approvals stay at the desk until you run portholed install-hooks")
	} else if err := cmdInstallHooks(); err != nil {
		return err
	}
	fmt.Println()
	if runtime.GOOS == "darwin" {
		fmt.Println("On a Mac: turn on Remote Login (System Settings > General > Sharing) so the phone")
		fmt.Println("has a way back in if the daemon ever stops. portholed keeps the Mac awake while a")
		fmt.Println("session works or a phone is connected, on the power adapter.")
	} else if out, err := probe("loginctl", "show-user", os.Getenv("USER"), "-p", "Linger", "--value"); err == nil && strings.TrimSpace(string(out)) != "yes" {
		fmt.Println("So the daemon keeps running after you log out - which is when you are away - run:")
		fmt.Println("    loginctl enable-linger " + os.Getenv("USER") + "      (with sudo if it asks)")
	}
	fmt.Println()
	fmt.Println("Next: portholed doctor, then portholed pair for the phone.")
	fmt.Println("Start Claude Code with porthole instead of claude, in your project's folder.")
	return nil
}

func cmdService(args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("usage: portholed service install|restart|status|uninstall")
	}
	switch args[0] {
	case "install":
		file, err := service.Install()
		if err != nil {
			return err
		}
		fmt.Println("installed and started:", file)
		line, _ := service.Status()
		fmt.Println(line)
	case "restart":
		return service.Restart()
	case "status":
		line, ok := service.Status()
		fmt.Println(line)
		if !ok {
			os.Exit(3)
		}
	case "uninstall":
		if err := service.Uninstall(); err != nil {
			return err
		}
		fmt.Println("stopped and removed; paired devices are kept in ~/.config/porthole")
	default:
		return fmt.Errorf("usage: portholed service install|restart|status|uninstall")
	}
	return nil
}
