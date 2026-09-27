// Package proto defines the wire frames between portholed and the app.
//
// Every frame carries V. Unknown Type values are ignored by both sides, so an older app
// against a newer daemon degrades instead of crashing.
package proto

const Version = 1

// Frame types, server -> client.
const (
	TypeHello             = "daemon.hello"
	TypeSessionList       = "session.list"
	TypeSessionRows       = "session.rows"
	TypeSessionEvent      = "session.event"
	TypeSessionStatus     = "session.status"  // what the CLI's own screen says it is doing
	TypeSessionTurn       = "session.turn"    // a turn finished in some live session
	TypeSessionWorking    = "session.working" // a live session started/stopped working, or changed tool
	TypePermissionRequest = "permission.request"
	TypeState             = "state"
	TypePTYData           = "pty.data"
	TypePTYOpen           = "pty.open"
	TypePTYClosed         = "pty.closed"
	TypePTYSize           = "pty.size"
	TypeError             = "error"
	TypeImageData         = "image.data" // bytes for an image row, or a fresh capture
	TypeClipData          = "clip.data"  // a short screen recording
	TypeChanges           = "changes"    // the answer to changes.get
)

// Frame types, client -> server.
const (
	TypeSessionAttach    = "session.attach"
	TypeSessionEarlier   = "session.earlier" // older rows of the attached session, before what the phone holds
	TypeSessionStart     = "session.start"   // start Claude Code in a known project directory: resume or fresh
	TypeSessionStarted   = "session.started" // the daemon's answer
	TypePromptSend       = "prompt.send"
	TypePermissionDecide = "permission.decide"
	TypeHookEvent        = "hook.event"
	TypePTYInput         = "pty.input"
	TypePTYResize        = "pty.resize"
	TypePTYScroll        = "pty.scroll" // walk the pane's history: lines > 0 back, < 0 forward, 0 back to live
	TypeSessionInterrupt = "session.interrupt"
	TypeSessionKey       = "session.key"    // Enter or Escape, nothing else
	TypeSessionAnswer    = "session.answer" // drive the question picker: option digit, typed text, Right (advance), Enter (submit)
	TypeChangesGet       = "changes.get"    // what git sees changed in the session's directory
	TypeClientReport     = "client.report"  // the phone telling the computer why it died
	TypeImageGet         = "image.get"
	TypeCaptureStill     = "capture.still"
	TypeCaptureClip      = "capture.clip"
	TypePreviewList      = "preview.list"  // ask what is listening; also the answer
	TypePreviewOpen      = "preview.open"  // share localhost:<port> with this device
	TypePreviewClose     = "preview.close" // stop sharing it
	TypePreviewState     = "preview.state" // the daemon's answer to open/close
	TypeSSHKey           = "ssh.key"       // add this phone's failsafe key (public_key set) or remove it (empty)
	TypeSSHKeyState      = "ssh.key.state" // the daemon's answer: how the failsafe would sign in now
)

// Capabilities advertised in Hello. The app shows an optional feature only when the
// daemon says it can actually do the thing, so features light up without an app update.
const (
	CapSessions  = "sessions"
	CapPrompt    = "prompt"
	CapTerminal  = "terminal"
	CapApprovals = "approvals"
	CapCapture   = "capture" // stills of the desktop (grim)
	CapRecord    = "record"  // short clips of the desktop (wf-recorder)
	CapUpload    = "upload"  // images attached to a prompt from the phone
	CapADB       = "adb"     // reserved; not advertised yet
	CapPreview   = "preview" // share a local dev server with the phone over the tailnet
	CapStart     = "start"   // start or resume Claude Code in a project directory from the phone
	CapChanges   = "changes" // the working tree's diff, from git, for a session's directory
	CapSSHKey    = "ssh_key" // can put the phone's own key in authorized_keys, for the failsafe
)

type Frame struct {
	V    int    `json:"v"`
	Type string `json:"type"`
}

type Hello struct {
	Frame
	DaemonVersion string   `json:"daemon_version"`
	Host          string   `json:"host"`
	OS            string   `json:"os"`
	Caps          []string `json:"caps"`
	DeviceName    string   `json:"device_name"` // who the daemon thinks you are
	// AutoContinue mirrors Claude Code's autoContinueAtUsageLimit user setting, read
	// from ~/.claude/settings.json (default true on a claude.ai subscription). The app
	// shows it; changing it is done at the desk, in /config.
	AutoContinue bool `json:"auto_continue"`
	// SSHUser is the account the failsafe should log in as. The app stores it while the
	// daemon is reachable, so that when the daemon is DOWN it already knows who to be.
	SSHUser string `json:"ssh_user,omitempty"`
	// Restart is the shell command that restarts the daemon on this computer, for the
	// failsafe to run over SSH when the daemon is down: learned while it was up.
	Restart string `json:"restart,omitempty"`
	// Failsafe is how the failsafe shell signs in from this phone: "tailscale" (Tailscale
	// SSH serves this machine), "key" (the phone's own key is in authorized_keys) or ""
	// (neither yet; the app offers to add a key where the daemon has CapSSHKey).
	Failsafe string `json:"failsafe,omitempty"`
	// SSHServer is true when something answers on port 22 here - on a Mac, Remote Login.
	// A key is no use without it.
	SSHServer bool `json:"ssh_server,omitempty"`
	// LatestBuild is the newest Porthole APK published on this computer, if any. Only
	// older apps read it; current ones ignore it and update from GitHub releases.
	LatestBuild *BuildInfo `json:"latest_build,omitempty"`
	// Builds is the newest published APK of every app being worked on at this computer,
	// so the phone can offer to install them.
	Builds []BuildInfo `json:"builds,omitempty"`
}

// SSHKeyState answers ssh.key with the failsafe as it stands after the change.
type SSHKeyState struct {
	Frame
	Failsafe  string `json:"failsafe"`
	SSHServer bool   `json:"ssh_server"`
	Error     string `json:"error,omitempty"`
}

// BuildInfo names a published APK; Path is relative to the daemon's HTTP root.
type BuildInfo struct {
	App     string `json:"app"`
	Version string `json:"version"`
	Path    string `json:"path"`
	Size    int64  `json:"size"`
	Code    int    `json:"code,omitempty"` // versionCode, when publish could read it
}

type Error struct {
	Frame
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Error codes the app switches on to pick a failure card.
const (
	ErrNotPaired  = "not_paired"
	ErrBadCode    = "bad_code"
	ErrRevoked    = "revoked"
	ErrNotTailnet = "not_tailnet"
	ErrForbidden  = "forbidden"
)

func NewHello(version, host, osName, deviceName, sshUser string, caps []string) Hello {
	return Hello{
		Frame:         Frame{V: Version, Type: TypeHello},
		DaemonVersion: version,
		Host:          host,
		OS:            osName,
		Caps:          caps,
		DeviceName:    deviceName,
		SSHUser:       sshUser,
	}
}

func NewError(code, msg string) Error {
	return Error{Frame: Frame{V: Version, Type: TypeError}, Code: code, Message: msg}
}
