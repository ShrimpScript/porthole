package server

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestListBuildsNewestFirstAndOnlyCanonicalNames(t *testing.T) {
	dir := t.TempDir()
	for _, n := range []string{"porthole-0.8.2.apk", "porthole-0.10.0.apk", "porthole-0.9.1.apk", "notes.txt", "Evil-0.9.9.apk", "my-app-1.4.0.apk", "my-app-1.3.9.apk"} {
		if err := os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "porthole-0.10.0.apk.json"), []byte(`{"code":17,"name":"0.10.0"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	got := listBuilds(dir)
	if got[0].Code != 17 || got[1].Code != 0 {
		t.Fatalf("version codes from sidecars: %+v", got[:2])
	}
	want := []string{"porthole 0.10.0", "porthole 0.9.1", "porthole 0.8.2", "my-app 1.4.0", "my-app 1.3.9"}
	if len(got) != len(want) {
		t.Fatalf("got %d builds, want %d: %+v", len(got), len(want), got)
	}
	for i := range want {
		if got[i].App+" "+got[i].Version != want[i] {
			t.Errorf("position %d: %s %s, want %s", i, got[i].App, got[i].Version, want[i])
		}
	}
	newest := newestBuilds(dir)
	if len(newest) != 2 || newest[0].Version != "0.10.0" || newest[1].App != "my-app" || newest[1].Version != "1.4.0" {
		t.Fatalf("newest per app: %+v", newest)
	}
	if latestBuild(dir, "nope") != nil {
		t.Fatal("unknown app has a latest build")
	}
	if DisplayName("shopping-list") != "Shopping list" || DisplayName("my-app") != "My app" {
		t.Fatal("display names")
	}
}

func TestPublishBuildNeedsAnApp(t *testing.T) {
	t.Setenv("PORTHOLE_STATE_DIR", t.TempDir())
	src := filepath.Join(t.TempDir(), "app-release.apk")
	if err := os.WriteFile(src, []byte("APKBYTES"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := PublishBuild(src, "1.4.0", ""); err == nil {
		t.Fatal("published a build with no app")
	}
	b, err := PublishBuild(src, "1.4.0", "shopping-list")
	if err != nil {
		t.Fatal(err)
	}
	if b.File != "shopping-list-1.4.0.apk" {
		t.Fatalf("file %q", b.File)
	}
	if _, err := os.Stat(filepath.Join(BuildsDir(), b.File)); err != nil {
		t.Fatal(err)
	}
}

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{{"0.9.0", "0.8.2", 1}, {"0.10.0", "0.9.1", 1}, {"0.9", "0.9.0", 0}, {"1.0", "0.99.99", 1}, {"0.8.2", "0.8.2", 0}, {"0.8.1", "0.8.2", -1}}
	for _, c := range cases {
		if got := compareVersions(c.a, c.b); got != c.want {
			t.Errorf("compare(%s, %s) = %d, want %d", c.a, c.b, got, c.want)
		}
	}
}

func TestBuildsHandlerServesGatesAndRefusesOtherNames(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "porthole-0.9.0.apk"), []byte("APKBYTES"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "secret.txt"), []byte("no"), 0o600); err != nil {
		t.Fatal(err)
	}
	allow := true
	srv := httptest.NewServer(buildsHandler(dir, func(*http.Request) bool { return allow }))
	defer srv.Close()
	get := func(p string) (int, string, string) {
		res, err := http.Get(srv.URL + p)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(res.Body)
		res.Body.Close()
		return res.StatusCode, string(body), res.Header.Get("Content-Type")
	}
	if code, body, ct := get("/builds/porthole-0.9.0.apk"); code != 200 || body != "APKBYTES" || ct != "application/vnd.android.package-archive" {
		t.Fatalf("apk: %d %q %q", code, body, ct)
	}
	if code, body, _ := get("/builds/latest.json"); code != 200 || !contains(body, `"version":"0.9.0"`) {
		t.Fatalf("latest: %d %q", code, body)
	}
	if code, body, _ := get("/builds/"); code != 200 || !contains(body, "Porthole 0.9.0") {
		t.Fatalf("page: %d %q", code, body)
	}
	if code, body, _ := get("/builds/index.json"); code != 200 || !contains(body, `"app":"porthole"`) {
		t.Fatalf("index: %d %q", code, body)
	}
	for _, p := range []string{"/builds/secret.txt", "/builds/../store.json", "/builds/Evil-0.9.0.apk", "/builds/x.apk"} {
		if code, _, _ := get(p); code != 404 {
			t.Errorf("%s: %d, want 404", p, code)
		}
	}
	allow = false
	if code, _, _ := get("/builds/porthole-0.9.0.apk"); code != 403 {
		t.Fatalf("unpaired got %d, want 403", code)
	}
}

func contains(s, sub string) bool {
	return len(sub) == 0 || (len(s) >= len(sub) && indexOf(s, sub) >= 0)
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}
