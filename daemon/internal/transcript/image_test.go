package transcript

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const imageSample = `{"type":"user","uuid":"u1","timestamp":"2026-09-11T08:00:00Z","message":{"role":"user","content":[{"type":"text","text":"what is this"},{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"AAA="}}]}}
{"type":"assistant","uuid":"a1","timestamp":"2026-09-11T08:00:02Z","message":{"model":"m","stop_reason":"tool_use","content":[{"type":"tool_use","id":"tu_1","name":"Read","input":{"file_path":"/tmp/x.png"}}]}}
{"type":"user","uuid":"u2","timestamp":"2026-09-11T08:00:03Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu_1","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"BBB="}}]}]}}
{"type":"assistant","uuid":"a2","timestamp":"2026-09-11T08:00:04Z","message":{"model":"m","stop_reason":"end_turn","content":[{"type":"tool_use","id":"tu_2","name":"SendUserFile","input":{"files":["/srv/pics/shot.png","/srv/pics/notes.txt"]}}]}}
`

func TestImageRows(t *testing.T) {
	res, err := Parse(strings.NewReader(imageSample))
	if err != nil {
		t.Fatal(err)
	}
	var refs []string
	for _, r := range res.Rows {
		if r.Kind == KindImage {
			refs = append(refs, r.ImageRef+"|"+r.Media+"|"+r.Text)
		}
	}
	want := []string{"u1:0|image/jpeg|Image you sent", "u2:0|image/png|Image Claude looked at", "file:/srv/pics/shot.png|image/png|shot.png"}
	if strings.Join(refs, ",") != strings.Join(want, ",") {
		t.Fatalf("image rows = %v, want %v", refs, want)
	}
}

func TestImageBlockLookup(t *testing.T) {
	p := filepath.Join(t.TempDir(), "s.jsonl")
	os.WriteFile(p, []byte(imageSample), 0o644)
	media, data, err := ImageBlock(p, "u2", 0)
	if err != nil || media != "image/png" || data != "BBB=" {
		t.Fatalf("got %q %q %v", media, data, err)
	}
	if _, _, err := ImageBlock(p, "u2", 1); err == nil {
		t.Fatal("second image should not exist")
	}
	if _, _, err := ImageBlock(p, "nope", 0); err == nil {
		t.Fatal("unknown uuid should fail")
	}
}
