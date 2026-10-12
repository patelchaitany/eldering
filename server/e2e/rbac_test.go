package e2e

import (
	"net/http"
	"strings"
	"testing"
	"time"
)

// SPEC: V sees S's live location iff V != S share >= 1 family AND
// grant(S->V) = 'live' AND S.sharing_state = 'on' (or paused_until has passed).
func TestLocationVisibility(t *testing.T) {
	t.Parallel()
	admin, s, v, x := newActor(t, "loc-admin"), newActor(t, "loc-sharer"), newActor(t, "loc-viewer"), newActor(t, "loc-outsider")
	fid, _ := newFamily(t, admin, s, v)
	x.createFamily(t, "e2e outsider family")
	const lat, lng = 52.370216, 4.895168
	s.heartbeat(t, lat, lng)

	check(t, "default_grant_is_emergency_without_live_location", func(t *testing.T) {
		v.expectView(t, s, "hidden", false)
		g, ok := s.grantTo(t, v)
		if !ok || g.Level != "emergency" || !g.IsDefault {
			t.Fatalf("want implicit emergency grant, got %+v (listed=%v)", g, ok)
		}
	})

	check(t, "viewer_cannot_write_the_sharers_grant", func(t *testing.T) {
		// PUT /v1/grants/{S} by V always writes V->S, never S->V.
		v.setGrant(t, s, "live")
		v.expectView(t, s, "hidden", false)
		if g, _ := s.grantTo(t, v); g.Level != "emergency" || !g.IsDefault {
			t.Fatalf("viewer changed the sharer's grant: %+v", g)
		}
		for _, m := range s.members(t, fid) {
			if m.UserID == v.id && (m.MyGrant == nil || *m.MyGrant != "emergency" || m.TheirGrant == nil || *m.TheirGrant != "live") {
				t.Fatalf("members view of grants wrong: %+v", m)
			}
		}
		v.setGrant(t, s, "emergency")
	})

	check(t, "live_grant_reveals_location", func(t *testing.T) {
		s.setGrant(t, v, "live")
		l := v.expectView(t, s, "live", true)
		if !near(*l.Lat, lat) || !near(*l.Lng, lng) {
			t.Fatalf("wrong coordinates: %v,%v", *l.Lat, *l.Lng)
		}
	})

	check(t, "admin_gets_no_extra_location_visibility", func(t *testing.T) {
		admin.expectView(t, s, "hidden", false)
		// The admin's member list shows only grants that involve the admin.
		for _, m := range admin.members(t, fid) {
			if (m.MyGrant != nil && *m.MyGrant == "live") || (m.TheirGrant != nil && *m.TheirGrant == "live") {
				t.Fatalf("admin sees a live grant that does not involve them: %+v", m)
			}
		}
	})

	check(t, "non_co_member_sees_nothing", func(t *testing.T) {
		x.expectNotListed(t, s, v, admin)
		wantErr(t, x.do(t, http.MethodGet, "/v1/families/"+fid+"/members", nil), http.StatusNotFound, "not_found")
	})

	check(t, "grant_off_hides_location", func(t *testing.T) {
		s.setGrant(t, v, "off")
		v.expectView(t, s, "hidden", false)
		s.setGrant(t, v, "live")
		v.expectView(t, s, "live", true)
	})

	check(t, "pause_hides_location_and_is_only_shown_to_live_viewers", func(t *testing.T) {
		s.settings(t, map[string]any{"sharing_state": "paused"})
		v.expectView(t, s, "paused", false)
		admin.expectView(t, s, "hidden", false)
		s.settings(t, map[string]any{"sharing_state": "on"})
		v.expectView(t, s, "live", true)
	})

	check(t, "elapsed_pause_shows_location_again", func(t *testing.T) {
		hb := s.heartbeat(t, lat, lng)
		until := hb.ServerTime.Add(3 * time.Second)
		s.settings(t, map[string]any{"sharing_state": "paused", "paused_until": until.Format(time.RFC3339Nano)})
		v.expectView(t, s, "paused", false)
		waitFor(t, 15*time.Second, "pause to elapse", func() bool {
			l, _ := v.view(t, s)
			return l.Status == "live" && l.Lat != nil
		})
		s.settings(t, map[string]any{"sharing_state": "on"})
	})

	check(t, "sharing_off_hides_and_discards_location", func(t *testing.T) {
		s.settings(t, map[string]any{"sharing_state": "off"})
		v.expectView(t, s, "hidden", false)
		s.heartbeat(t, lat+0.01, lng) // must not be stored while off
		s.settings(t, map[string]any{"sharing_state": "on"})
		v.expectView(t, s, "live", false) // nothing kept from before or during "off"
		s.heartbeat(t, lat, lng)
		v.expectView(t, s, "live", true)
	})

	check(t, "grants_only_towards_co_members", func(t *testing.T) {
		toOutsider := s.do(t, http.MethodPut, "/v1/grants/"+x.id, map[string]string{"level": "live"})
		toNobody := s.do(t, http.MethodPut, "/v1/grants/"+randUUID(), map[string]string{"level": "live"})
		wantErr(t, toOutsider, http.StatusNotFound, "not_found")
		sameResponse(t, toOutsider, toNobody)
		wantErr(t, s.do(t, http.MethodPut, "/v1/grants/"+s.id, map[string]string{"level": "live"}), http.StatusBadRequest, "invalid_viewer")
		wantErr(t, s.do(t, http.MethodPut, "/v1/grants/"+v.id, map[string]string{"level": "admin"}), http.StatusBadRequest, "invalid_level")
		x.expectNotListed(t, s)
	})

	check(t, "grant_list_is_outgoing_and_co_members_only", func(t *testing.T) {
		got := map[string]string{}
		for _, g := range s.grants(t) {
			got[g.ViewerID] = g.Level
		}
		if len(got) != 2 || got[v.id] != "live" || got[admin.id] != "emergency" {
			t.Fatalf("sharer's grant list wrong: %v", got)
		}
		for _, g := range v.grants(t) {
			if g.ViewerID == s.id && g.Level != "emergency" {
				t.Fatalf("viewer's list shows an incoming grant: %+v", g)
			}
			if g.ViewerID == x.id || g.ViewerID == v.id {
				t.Fatalf("unexpected grant entry: %+v", g)
			}
		}
	})

	check(t, "family_views_never_expose_other_users_emails", func(t *testing.T) {
		for _, r := range []*response{
			v.must(t, http.MethodGet, "/v1/families/"+fid+"/members", nil, http.StatusOK),
			v.must(t, http.MethodGet, "/v1/family/locations", nil, http.StatusOK),
			v.must(t, http.MethodGet, "/v1/grants", nil, http.StatusOK),
			v.must(t, http.MethodGet, "/v1/me", nil, http.StatusOK),
		} {
			notContains(t, r, s.email, admin.email, "password")
		}
	})
}

// SPEC: V sees S's incidents/trail iff co-members AND grant(S->V) in {live,
// emergency} AND (S.sharing_state = 'on' OR S.always_alert_on_accident).
// grant 'off' => never.
func TestIncidentVisibilityMatrix(t *testing.T) {
	t.Parallel()
	admin, s, v, x := newActor(t, "inc-admin"), newActor(t, "inc-sharer"), newActor(t, "inc-viewer"), newActor(t, "inc-outsider")
	newFamily(t, admin, s, v)
	x.createFamily(t, "e2e outsider family")
	inc := s.newIncident(t, "vehicle_crash", 19.076, 72.8777, "Marine Drive")

	cases := []struct {
		name        string
		sharing     string
		alwaysAlert bool
		grant       string
		visible     bool
	}{
		{"default_emergency_grant_sharing_on", "on", true, "emergency", true},
		{"live_grant_sharing_on", "on", true, "live", true},
		{"grant_off_never_even_with_always_alert", "on", true, "off", false},
		{"sharing_on_without_always_alert", "on", false, "emergency", true},
		{"paused_with_always_alert", "paused", true, "emergency", true},
		{"paused_without_always_alert", "paused", false, "emergency", false},
		{"paused_live_grant_without_always_alert", "paused", false, "live", false},
		{"sharing_off_with_always_alert", "off", true, "emergency", true},
		{"sharing_off_without_always_alert", "off", false, "live", false},
		{"sharing_off_grant_off_with_always_alert", "off", true, "off", false},
	}
	for _, c := range cases {
		check(t, c.name, func(t *testing.T) {
			s.settings(t, map[string]any{"sharing_state": c.sharing, "always_alert_on_accident": c.alwaysAlert})
			s.setGrant(t, v, c.grant)
			v.expectIncident(t, inc.ID, c.visible)
			x.expectIncident(t, inc.ID, false)
		})
	}

	check(t, "elapsed_pause_counts_as_sharing_on", func(t *testing.T) {
		s.setGrant(t, v, "emergency")
		hb := s.heartbeat(t, 19.07, 72.87)
		until := hb.ServerTime.Add(3 * time.Second)
		s.settings(t, map[string]any{"sharing_state": "paused", "paused_until": until.Format(time.RFC3339Nano), "always_alert_on_accident": false})
		v.expectIncident(t, inc.ID, false)
		waitFor(t, 15*time.Second, "pause to elapse", func() bool { return v.incidentVisibility(t, inc.ID) })
	})

	check(t, "admin_gets_no_extra_incident_visibility", func(t *testing.T) {
		s.settings(t, map[string]any{"sharing_state": "on", "always_alert_on_accident": true})
		s.setGrant(t, v, "emergency")
		s.setGrant(t, admin, "off")
		admin.expectIncident(t, inc.ID, false)
		v.expectIncident(t, inc.ID, true)
		s.setGrant(t, admin, "emergency")
		admin.expectIncident(t, inc.ID, true)
	})

	check(t, "owner_always_sees_own_incident", func(t *testing.T) {
		s.settings(t, map[string]any{"sharing_state": "off", "always_alert_on_accident": false})
		var d summaryJSON
		s.must(t, http.MethodGet, "/v1/incidents/"+inc.ID, nil, http.StatusOK).decode(t, &d)
		if !d.IsMine || d.UserID != s.id {
			t.Fatalf("owner view wrong: %+v", d)
		}
		if hb := s.heartbeat(t, 1, 1); containsIncident(hb.PendingAlerts, inc.ID) {
			t.Fatalf("own incident must not be a pending alert")
		}
	})
}

// SPEC: admin = create invites, remove members, rename family; leaving or
// removal revokes visibility immediately.
func TestMembershipRevocation(t *testing.T) {
	t.Parallel()
	admin, s, v, x := newActor(t, "mem-admin"), newActor(t, "mem-sharer"), newActor(t, "mem-viewer"), newActor(t, "mem-outsider")
	fid, code0 := admin.createFamily(t, "e2e membership")
	s.mustJoin(t, code0)
	// Codes are typed by people: lower case and dashes are accepted.
	v.mustJoin(t, strings.ToLower(code0[:4])+"-"+strings.ToLower(code0[4:]))
	x.createFamily(t, "e2e outsider family")
	missing := randUUID()

	var code1 string
	check(t, "invites_admin_only", func(t *testing.T) {
		wantErr(t, s.do(t, http.MethodPost, "/v1/families/"+fid+"/invites", nil), http.StatusForbidden, "forbidden")
		foreign := x.do(t, http.MethodPost, "/v1/families/"+fid+"/invites", nil)
		wantErr(t, foreign, http.StatusNotFound, "not_found")
		sameResponse(t, foreign, x.do(t, http.MethodPost, "/v1/families/"+missing+"/invites", nil))
		var inv inviteJSON
		admin.must(t, http.MethodPost, "/v1/families/"+fid+"/invites", nil, http.StatusCreated).decode(t, &inv)
		if len(inv.Code) != 8 || strings.ContainsAny(inv.Code, "ILOU") {
			t.Fatalf("bad invite code %q", inv.Code)
		}
		if d := time.Until(inv.ExpiresAt); d < 47*time.Hour || d > 49*time.Hour {
			t.Fatalf("invite should expire in 48 h, got %v", d)
		}
		code1 = inv.Code
	})

	check(t, "rename_admin_only", func(t *testing.T) {
		body := map[string]string{"name": "renamed " + randHex(2)}
		wantErr(t, s.do(t, http.MethodPatch, "/v1/families/"+fid, body), http.StatusForbidden, "forbidden")
		foreign := x.do(t, http.MethodPatch, "/v1/families/"+fid, body)
		wantErr(t, foreign, http.StatusNotFound, "not_found")
		sameResponse(t, foreign, x.do(t, http.MethodPatch, "/v1/families/"+missing, body))
		admin.must(t, http.MethodPatch, "/v1/families/"+fid, body, http.StatusOK)
	})

	check(t, "member_list_for_members_only", func(t *testing.T) {
		foreign := x.do(t, http.MethodGet, "/v1/families/"+fid+"/members", nil)
		wantErr(t, foreign, http.StatusNotFound, "not_found")
		sameResponse(t, foreign, x.do(t, http.MethodGet, "/v1/families/"+missing+"/members", nil))
		if ms := s.members(t, fid); len(ms) != 3 {
			t.Fatalf("want 3 members, got %+v", ms)
		}
	})

	check(t, "removing_others_requires_admin", func(t *testing.T) {
		wantErr(t, s.do(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+v.id, nil), http.StatusForbidden, "forbidden")
		wantErr(t, s.do(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+admin.id, nil), http.StatusForbidden, "forbidden")
		wantErr(t, v.do(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+admin.id, nil), http.StatusForbidden, "forbidden")
		foreign := x.do(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+v.id, nil)
		wantErr(t, foreign, http.StatusNotFound, "not_found")
		sameResponse(t, foreign, x.do(t, http.MethodDelete, "/v1/families/"+missing+"/members/"+v.id, nil))
		// Admin removing someone who is not a member: same 404 as a nonexistent user.
		notMember := admin.do(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+x.id, nil)
		wantErr(t, notMember, http.StatusNotFound, "not_found")
		sameResponse(t, notMember, admin.do(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+missing, nil))
		if ms := s.members(t, fid); len(ms) != 3 {
			t.Fatalf("membership changed: %+v", ms)
		}
	})

	s.setGrant(t, v, "live")
	s.heartbeat(t, 40.7128, -74.006)
	inc := s.newIncident(t, "pedestrian_impact", 40.7128, -74.006, "Broadway")

	check(t, "precondition_viewer_sees_sharer", func(t *testing.T) {
		v.expectView(t, s, "live", true)
		v.expectIncident(t, inc.ID, true)
	})

	check(t, "admin_removal_revokes_immediately", func(t *testing.T) {
		admin.must(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+v.id, nil, http.StatusNoContent)
		v.expectNotListed(t, s, admin)
		v.expectIncident(t, inc.ID, false)
		wantErr(t, v.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/ack", map[string]string{"state": "on_my_way"}), http.StatusNotFound, "not_found")
		wantErr(t, v.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/resolve", map[string]string{"resolution": "handled"}), http.StatusNotFound, "not_found")
		wantErr(t, v.do(t, http.MethodGet, "/v1/families/"+fid+"/members", nil), http.StatusNotFound, "not_found")
		for _, f := range v.me(t).Families {
			if f.ID == fid {
				t.Fatalf("removed member still lists the family")
			}
		}
		if _, listed := s.grantTo(t, v); listed {
			t.Fatalf("sharer still lists a grant to a removed member")
		}
	})

	check(t, "removed_member_cannot_rejoin_with_a_known_invite", func(t *testing.T) {
		for _, c := range []string{code0, code1} {
			if c == "" {
				continue
			}
			wantErr(t, v.join(t, c), http.StatusNotFound, "invite_invalid")
		}
		v.expectNotListed(t, s)
		v.expectIncident(t, inc.ID, false)
	})

	check(t, "rejoin_with_fresh_invite_resets_grants_to_default", func(t *testing.T) {
		v.mustJoin(t, admin.invite(t, fid))
		v.expectView(t, s, "hidden", false) // the old 'live' grant did not survive
		if g, ok := s.grantTo(t, v); !ok || g.Level != "emergency" || !g.IsDefault {
			t.Fatalf("grant after re-join: %+v (listed=%v)", g, ok)
		}
		v.expectIncident(t, inc.ID, true) // emergency default
	})

	check(t, "leaving_revokes_immediately", func(t *testing.T) {
		s.setGrant(t, v, "live")
		v.expectView(t, s, "live", true)
		s.must(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+s.id, nil, http.StatusNoContent)
		v.expectNotListed(t, s)
		v.expectIncident(t, inc.ID, false)
		wantErr(t, s.do(t, http.MethodGet, "/v1/families/"+fid+"/members", nil), http.StatusNotFound, "not_found")
	})

	check(t, "last_admin_leaving_promotes_a_member", func(t *testing.T) {
		admin.must(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+admin.id, nil, http.StatusNoContent)
		ms := v.members(t, fid)
		if len(ms) != 1 || ms[0].UserID != v.id || ms[0].Role != "admin" {
			t.Fatalf("want the remaining member promoted, got %+v", ms)
		}
	})
}

// Grants are effective while two people share ANY family.
func TestCoMembershipAcrossFamilies(t *testing.T) {
	t.Parallel()
	p, q := newActor(t, "multi-sharer"), newActor(t, "multi-viewer")
	g, _ := newFamily(t, p, q)
	h, _ := newFamily(t, p, q)
	p.setGrant(t, q, "live")
	p.heartbeat(t, -33.8688, 151.2093)

	check(t, "leaving_one_shared_family_keeps_visibility", func(t *testing.T) {
		q.must(t, http.MethodDelete, "/v1/families/"+h+"/members/"+q.id, nil, http.StatusNoContent)
		q.expectView(t, p, "live", true)
		if gr, ok := p.grantTo(t, q); !ok || gr.Level != "live" {
			t.Fatalf("grant lost while still co-members: %+v", gr)
		}
	})

	check(t, "leaving_the_last_shared_family_revokes_and_prunes", func(t *testing.T) {
		q.must(t, http.MethodDelete, "/v1/families/"+g+"/members/"+q.id, nil, http.StatusNoContent)
		q.expectNotListed(t, p)
		if _, ok := p.grantTo(t, q); ok {
			t.Fatalf("grant still listed after the last shared family")
		}
	})
}
