package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/inbox"
	"github.com/shrimpscript/porthole/daemon/internal/server"
	"github.com/shrimpscript/porthole/daemon/internal/store"
)

const inboxUsage = `usage:
  portholed inbox                 the questions waiting on you, by project (-json, -full)
  portholed inbox add DIR...      read these folders, and every folder directly inside them
  portholed inbox remove DIR...   stop reading them (a project inside one: skip just it)
  portholed inbox name NAME       what older notes call you ("Waiting on NAME")
  portholed inbox answer PROJECT/ID TEXT  record an answer in the project's decisions file, and type
                                  it to a Claude Code session running there
  portholed inbox dismiss PROJECT/ID      put a question aside (kept on this computer only)`

func cmdInbox(args []string) error {
	dir := store.Dir()
	c, err := inbox.LoadConfig(dir)
	if err != nil {
		return err
	}
	sub := ""
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		sub, args = args[0], args[1:]
	}
	switch sub {
	case "":
		return inboxList(c, args)
	case "add":
		if len(args) == 0 {
			return errors.New(inboxUsage)
		}
		for _, a := range args {
			p := inbox.Expand(a)
			if st, err := os.Stat(p); err != nil || !st.IsDir() {
				return fmt.Errorf("%s is not a folder", a)
			}
			if !contains(c.Folders, p) {
				c.Folders = append(c.Folders, p)
			}
		}
		return saveInbox(dir, c)
	case "remove":
		if len(args) == 0 {
			return errors.New(inboxUsage)
		}
		for _, a := range args {
			p := inbox.Expand(a)
			var keep []string
			for _, f := range c.Folders {
				if !sameDir(inbox.Expand(f), p) {
					keep = append(keep, f)
				}
			}
			if len(keep) == len(c.Folders) && !contains(c.Skip, p) {
				c.Skip = append(c.Skip, p) // a project found inside a folder: skip just it
			}
			c.Folders = keep
		}
		return saveInbox(dir, c)
	case "name":
		if len(args) != 1 {
			return errors.New(inboxUsage)
		}
		c.Name = strings.TrimSpace(args[0])
		return saveInbox(dir, c)
	case "answer":
		if len(args) < 2 {
			return errors.New(inboxUsage)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		dir, id, err := resolveItem(c, args[0])
		if err != nil {
			return err
		}
		rel, told, err := server.AnswerInbox(ctx, dir, id, strings.Join(args[1:], " "))
		if err != nil {
			return err
		}
		fmt.Println("Recorded in " + rel + ".")
		if told != "" {
			fmt.Println("Told the session " + told + ".")
		}
		return nil
	case "dismiss":
		if len(args) != 1 {
			return errors.New(inboxUsage)
		}
		pdir, id, err := resolveItem(c, args[0])
		if err != nil {
			return err
		}
		return inbox.Dismiss(dir, pdir, id)
	default:
		return errors.New(inboxUsage)
	}
}

func saveInbox(dir string, c inbox.Config) error {
	if err := inbox.SaveConfig(dir, c); err != nil {
		return err
	}
	fmt.Printf("Reading %d folder(s)", len(c.Folders))
	if c.Name != "" {
		fmt.Printf(", notes that wait on you or %s", c.Name)
	}
	fmt.Println(".")
	return nil
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func inboxList(c inbox.Config, args []string) error {
	asJSON, full := false, false
	for _, a := range args {
		switch a {
		case "-json", "--json":
			asJSON = true
		case "-full", "--full":
			full = true
		default:
			return errors.New(inboxUsage)
		}
	}
	if len(c.Folders) == 0 && !asJSON {
		fmt.Println("No folders yet. Name the folder your projects are in:")
		fmt.Println("  portholed inbox add ~/code")
		fmt.Println("and, if your notes say \"Waiting on <your name>\":")
		fmt.Println("  portholed inbox name <your name>")
		return nil
	}
	list := inbox.Scan(c, inbox.Dismissed(store.Dir()))
	if asJSON {
		if list == nil {
			list = []inbox.Project{}
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(list)
	}
	if len(list) == 0 {
		fmt.Println("Nothing is waiting on you.")
		return nil
	}
	total := 0
	for _, p := range list {
		total += len(p.Items)
	}
	fmt.Printf("%d waiting, in %d project(s)\n", total, len(list))
	for _, p := range list {
		fmt.Printf("\n%s  (%d)\n", p.Name, len(p.Items))
		for _, it := range p.Items {
			text := it.Text
			if !full && len([]rune(text)) > 110 {
				text = string([]rune(text)[:109]) + "…"
			}
			fmt.Printf("  %-8s %s\n", humanAgo(it.Since), text)
			extra := ""
			if len(it.Options) > 0 {
				extra = "  choices: " + strings.Join(it.Options, " | ")
			}
			if it.Default != "" {
				extra += "  suggested: " + it.Default
			}
			fmt.Printf("  %-8s %s:%d  %s/%s%s\n", "", it.File, it.Line, p.Name, it.ID, extra)
		}
	}
	return nil
}

func sameDir(a, b string) bool {
	ra, err1 := filepath.EvalSymlinks(a)
	rb, err2 := filepath.EvalSymlinks(b)
	if err1 != nil || err2 != nil {
		return a == b
	}
	return ra == rb
}

// resolveItem finds the project of "PROJECT/ID", or of a bare ID that only one project has.
func resolveItem(c inbox.Config, ref string) (string, string, error) {
	name, id := "", ref
	if i := strings.LastIndex(ref, "/"); i >= 0 {
		name, id = ref[:i], ref[i+1:]
	}
	var dirs []string
	for _, p := range inbox.Scan(c, nil) {
		if name != "" && p.Name != name {
			continue
		}
		for _, it := range p.Items {
			if it.ID == id {
				dirs = append(dirs, p.Dir)
			}
		}
	}
	switch len(dirs) {
	case 0:
		return "", "", fmt.Errorf("no open question %s (see portholed inbox)", ref)
	case 1:
		return dirs[0], id, nil
	default:
		return "", "", fmt.Errorf("%d projects have a question %s: name the project too, as PROJECT/%s", len(dirs), id, id)
	}
}
