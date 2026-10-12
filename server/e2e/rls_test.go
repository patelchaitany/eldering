package e2e

import (
	"fmt"
	"net/http"
	"os"
	"regexp"
	"strings"
	"testing"
)

var rlsLine = regexp.MustCompile(`(PASS|FAIL|SKIP): ([A-Za-z0-9_.]+)(.*)`)

// TestRLSAppRole runs rls_app_role.sql as the app role (see that file). It
// first commits data through the public API so that "no identity => no rows"
// is checked against non-empty tables, and passes those ids to the script for
// positive checks.
func TestRLSAppRole(t *testing.T) {
	if cfg.psql == "" {
		check(t, "direct_sql", func(t *testing.T) { skip(t, "E2E_PSQL not set") })
		return
	}
	sharer, viewer := newActor(t, "rls-sharer"), newActor(t, "rls-viewer")
	newFamily(t, sharer, viewer)
	sharer.setGrant(t, viewer, "live")
	sharer.heartbeat(t, 12.9716, 77.5946)
	inc := sharer.newIncident(t, "fall", 12.9716, 77.5946, "MG Road")
	viewer.must(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/ack", map[string]string{"state": "seen"}, http.StatusOK)
	for _, id := range []string{sharer.id, viewer.id, inc.ID} {
		if !uuidRE.MatchString(id) {
			t.Fatalf("refusing to interpolate %q into SQL", id)
		}
	}

	sql, err := os.ReadFile("rls_app_role.sql")
	if err != nil {
		t.Fatal(err)
	}
	script := fmt.Sprintf("\\set sharer '%s'\n\\set viewer '%s'\n\\set incident '%s'\n", sharer.id, viewer.id, inc.ID) + string(sql)
	out, runErr := runPSQL(t, script)

	seen := map[string]bool{}
	results := 0
	for _, line := range strings.Split(out, "\n") {
		m := rlsLine.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		verdict, name, detail := m[1], m[2], strings.TrimSpace(m[3])
		if seen[name] {
			t.Errorf("duplicate check name %q in rls_app_role.sql", name)
		}
		seen[name] = true
		results++
		check(t, name, func(t *testing.T) {
			switch verdict {
			case "FAIL":
				t.Errorf("SQL check failed %s", detail)
			case "SKIP":
				skip(t, detail)
			}
		})
	}
	if runErr != nil || !strings.Contains(out, "RLS-CHECKS-COMPLETE") {
		t.Fatalf("psql did not complete (%v):\n%s", runErr, out)
	}
	if results < 100 {
		t.Fatalf("only %d SQL checks reported; output:\n%s", results, out)
	}
}
