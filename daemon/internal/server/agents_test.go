package server

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// A phone attached to a session hears about its subagents: an agents frame with each one's
// progress, read from the agent's own transcript, as soon as the attach lands.
func TestAttachReportsTheSessionsAgents(t *testing.T) {
	cwd := liveSession(t, "sess-a")
	root := filepath.Dir(cwd)
	tr := filepath.Join(root, "projects", transcript.ProjectSlug(cwd), "sess-a.jsonl")
	dir := session.SubagentsDir(tr)
	now := time.Now().UTC()
	write(t, dir, "agent-a1.meta.json", `{"agentType":"general-purpose","description":"Fix the flaky test","toolUseId":"toolu_1","spawnDepth":1,"requestShape":"background"}`)
	write(t, dir, "agent-a1.jsonl", fmt.Sprintf(
		`{"type":"user","timestamp":%q,"message":{"content":"Fix it"}}`+"\n"+
			`{"type":"assistant","timestamp":%q,"message":{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"b","name":"Bash","input":{"command":"npm test"}}]}}`+"\n",
		now.Add(-time.Minute).Format(time.RFC3339Nano), now.Format(time.RFC3339Nano)))

	c, ctx := dialPaired(t)
	sendJSON(t, ctx, c, map[string]any{"v": proto.Version, "type": proto.TypeSessionAttach, "session_id": "sess-a"})
	for {
		_, b, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("no agents frame: %v", err)
		}
		var f struct {
			Type      string          `json:"type"`
			SessionID string          `json:"session_id"`
			Agents    []session.Agent `json:"agents"`
		}
		if json.Unmarshal(b, &f) != nil || f.Type != proto.TypeSessionAgents {
			continue
		}
		if f.SessionID != "sess-a" || len(f.Agents) != 1 {
			t.Fatalf("agents frame: %s", b)
		}
		a := f.Agents[0]
		if a.State != "running" || a.Doing != "Bash: npm test" || a.ToolID != "toolu_1" || !a.Background || a.Tools != 1 {
			t.Fatalf("agent: %+v", a)
		}
		return
	}
}
