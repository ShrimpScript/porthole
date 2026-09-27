package server

// Builds: APKs of the apps being worked on at the computer, offered to paired phones over
// the tailnet and gated like everything else. `portholed publish <apk> <version> <app>`
// drops the file into <state>/builds as <app>-<version>.apk; Hello announces the newest
// build of every app so the phone can offer to install or update it; GET /builds/<file>
// serves it to a paired device; GET /builds/ is a plain page for a browser on the phone.
// Porthole itself updates from its GitHub releases, not from here.

import (
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
)

// Build is one published APK of one app.
type Build struct {
	App     string    `json:"app"`
	Version string    `json:"version"`
	File    string    `json:"file"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"-"`
	// Code is the APK's versionCode when publish could read it (aapt2 on the PATH or
	// under an Android SDK), else 0. The phone compares it; Android refuses a downgrade.
	Code int `json:"code,omitempty"`
}

// apkVersion reads versionCode and versionName from an APK with aapt2, when available.
func apkVersion(apk string) (code int, name string) {
	tool, _ := exec.LookPath("aapt2")
	if tool == "" {
		for _, root := range []string{os.Getenv("ANDROID_HOME"), os.Getenv("ANDROID_SDK_ROOT"), filepath.Join(os.Getenv("HOME"), "Android", "Sdk")} {
			if root == "" {
				continue
			}
			matches, _ := filepath.Glob(filepath.Join(root, "build-tools", "*", "aapt2"))
			if len(matches) > 0 {
				sort.Strings(matches)
				tool = matches[len(matches)-1]
				break
			}
		}
	}
	if tool == "" {
		return 0, ""
	}
	out, err := exec.Command(tool, "dump", "badging", apk).Output()
	if err != nil {
		return 0, ""
	}
	m := badging.FindStringSubmatch(string(out))
	if m == nil {
		return 0, ""
	}
	code, _ = strconv.Atoi(m[1])
	return code, m[2]
}

var badging = regexp.MustCompile(`versionCode='(\d+)' versionName='([^']*)'`)

// <app>-<version>.apk: the app is a short lowercase slug, the version dotted numbers.
var buildName = regexp.MustCompile(`^([a-z0-9][a-z0-9-]*?)-(\d+(?:\.\d+)*)\.apk$`)
var versionShape = regexp.MustCompile(`^\d+(?:\.\d+)*$`)
var appShape = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)

// DefaultApp is Porthole itself. Older versions of the app updated themselves from builds
// published under this name, so Hello and latest.json still single it out.
const DefaultApp = "porthole"

// BuildsDir is where published APKs live: beside uploads and captures, under the
// state directory, which is the one place the service unit lets the daemon write.
func BuildsDir() string {
	d := filepath.Join(filepath.Dir(uploadsDir()), "builds")
	_ = os.MkdirAll(d, 0o700)
	return d
}

// PublishBuild copies an APK into the builds directory under its canonical name.
func PublishBuild(src, version, app string) (Build, error) {
	if !versionShape.MatchString(version) {
		return Build{}, fmt.Errorf("version %q must look like 0.9.0", version)
	}
	if !appShape.MatchString(app) {
		return Build{}, fmt.Errorf("app %q must be a lowercase slug like my-app or shopping-list", app)
	}
	in, err := os.Open(src)
	if err != nil {
		return Build{}, err
	}
	defer in.Close()
	name := app + "-" + version + ".apk"
	dst := filepath.Join(BuildsDir(), name)
	tmp := dst + ".part"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return Build{}, err
	}
	n, err := io.Copy(out, in)
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = os.Remove(tmp)
		return Build{}, err
	}
	if err := os.Rename(tmp, dst); err != nil {
		return Build{}, err
	}
	b := Build{App: app, Version: version, File: name, Size: n, ModTime: time.Now()}
	if code, vname := apkVersion(dst); code > 0 {
		b.Code = code
		if vname != "" && vname != version {
			fmt.Fprintf(os.Stderr, "warning: the APK says version %s, published as %s\n", vname, version)
		}
		_ = os.WriteFile(dst+".json", []byte(fmt.Sprintf(`{"code":%d,"name":%q}`, code, vname)), 0o600)
	}
	return b, nil
}

// listBuilds returns the APKs in dir, grouped by app (Porthole first, then by name),
// newest version first within an app.
func listBuilds(dir string) []Build {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []Build
	for _, e := range entries {
		m := buildName.FindStringSubmatch(e.Name())
		if m == nil || e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		b := Build{App: m[1], Version: m[2], File: e.Name(), Size: info.Size(), ModTime: info.ModTime()}
		if raw, err := os.ReadFile(filepath.Join(dir, e.Name()+".json")); err == nil {
			var side struct {
				Code int `json:"code"`
			}
			if json.Unmarshal(raw, &side) == nil {
				b.Code = side.Code
			}
		}
		out = append(out, b)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].App != out[j].App {
			if out[i].App == DefaultApp || out[j].App == DefaultApp {
				return out[i].App == DefaultApp
			}
			return out[i].App < out[j].App
		}
		if c := compareVersions(out[i].Version, out[j].Version); c != 0 {
			return c > 0
		}
		return out[i].ModTime.After(out[j].ModTime)
	})
	return out
}

// newestBuilds is the newest version of every app, in listBuilds order.
func newestBuilds(dir string) []Build {
	var out []Build
	seen := map[string]bool{}
	for _, b := range listBuilds(dir) {
		if !seen[b.App] {
			seen[b.App] = true
			out = append(out, b)
		}
	}
	return out
}

// compareVersions orders dotted numbers: 0.10.0 > 0.9.1 > 0.9 (missing parts are 0).
func compareVersions(a, b string) int {
	as, bs := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(as) || i < len(bs); i++ {
		var x, y int
		if i < len(as) {
			x, _ = strconv.Atoi(as[i])
		}
		if i < len(bs) {
			y, _ = strconv.Atoi(bs[i])
		}
		if x != y {
			if x > y {
				return 1
			}
			return -1
		}
	}
	return 0
}

// latestBuild is the newest build of one app, or nil.
func latestBuild(dir, app string) *Build {
	for _, b := range listBuilds(dir) {
		if b.App == app {
			return &b
		}
	}
	return nil
}

func (s *Server) handleBuilds(w http.ResponseWriter, r *http.Request) {
	buildsHandler(BuildsDir(), s.previewAllowed).ServeHTTP(w, r)
}

// buildsHandler serves the builds page, latest.json and the APKs themselves to callers
// that pass the device gate. File names are matched against the canonical pattern, so
// nothing outside the directory can be named.
func buildsHandler(dir string, allow func(*http.Request) bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if !allow(r) {
			http.Error(w, "this device is not paired with portholed", http.StatusForbidden)
			return
		}
		name := strings.TrimPrefix(r.URL.Path, "/builds/")
		switch {
		case name == "":
			writeBuildsPage(w, listBuilds(dir))
		case name == "latest.json":
			app := r.URL.Query().Get("app")
			if app == "" {
				app = DefaultApp
			}
			b := latestBuild(dir, app)
			if b == nil {
				http.Error(w, "no builds published for "+app, http.StatusNotFound)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"app": b.App, "version": b.Version, "file": b.File, "size": b.Size, "path": "/builds/" + b.File,
			})
		case name == "index.json":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(buildInfos(newestBuilds(dir)))
		default:
			if !buildName.MatchString(name) {
				http.NotFound(w, r)
				return
			}
			path := filepath.Join(dir, name)
			if _, err := os.Stat(path); err != nil {
				http.NotFound(w, r)
				return
			}
			w.Header().Set("Content-Type", "application/vnd.android.package-archive")
			w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
			w.Header().Set("Cache-Control", "no-store")
			http.ServeFile(w, r, path)
		}
	})
}

func writeBuildsPage(w http.ResponseWriter, builds []Build) {
	host, _ := os.Hostname()
	var b strings.Builder
	b.WriteString(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">`)
	b.WriteString(`<title>Porthole builds</title><style>
body{margin:0;padding:24px 16px;background:#07100F;color:#E8F0EE;font:16px/1.45 system-ui,sans-serif}
h1{font-size:22px;margin:0 0 4px}p{color:#8FA3A0;margin:0 0 20px}
.b{background:#0E1A19;border-radius:14px;padding:14px 16px;margin:0 0 10px;display:flex;align-items:center;gap:12px}
.v{font-weight:600}.m{color:#7B908C;font-size:13px}.s{flex:1}
a.d{background:#3FD4C0;color:#07100F;text-decoration:none;font-weight:600;padding:10px 16px;border-radius:999px;white-space:nowrap}
.n{color:#7B908C}
</style>`)
	fmt.Fprintf(&b, `<h1>Porthole builds on %s</h1>`, html.EscapeString(host))
	b.WriteString(`<p>Tap Download, then open the file from the download notice. Android asks once to allow installs from Chrome.</p>`)
	if len(builds) == 0 {
		b.WriteString(`<div class="n">Nothing published yet. On the computer: <code>portholed publish app-release.apk 0.9.0 my-app</code></div>`)
	}
	lastApp := ""
	for _, x := range builds {
		tag := ""
		if x.App != lastApp {
			tag = " &middot; newest"
			lastApp = x.App
		}
		fmt.Fprintf(&b, `<div class="b"><div class="s"><div class="v">%s %s%s</div><div class="m">%s &middot; %s</div></div><a class="d" href="/builds/%s">Download</a></div>`,
			html.EscapeString(DisplayName(x.App)), html.EscapeString(x.Version), tag, humanBytes(x.Size), x.ModTime.Format("Jan 2, 15:04"), html.EscapeString(x.File))
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.WriteString(w, b.String())
}

// DisplayName turns a slug into a label: "shopping-list" -> "Shopping list".
func DisplayName(app string) string {
	if app == "" {
		return ""
	}
	t := strings.ReplaceAll(app, "-", " ")
	return strings.ToUpper(t[:1]) + t[1:]
}

func buildInfos(bs []Build) []proto.BuildInfo {
	out := make([]proto.BuildInfo, 0, len(bs))
	for _, b := range bs {
		out = append(out, proto.BuildInfo{App: b.App, Version: b.Version, Path: "/builds/" + b.File, Size: b.Size, Code: b.Code})
	}
	return out
}

func humanBytes(n int64) string {
	switch {
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/float64(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%d KB", n/(1<<10))
	}
	return fmt.Sprintf("%d B", n)
}
