package rbac

import "testing"

func TestParsers(t *testing.T) {
	for _, s := range []string{"live", "emergency", "off"} {
		if _, ok := ParseLevel(s); !ok {
			t.Errorf("level %q rejected", s)
		}
	}
	for _, s := range []string{"", "LIVE", "admin", "on"} {
		if _, ok := ParseLevel(s); ok {
			t.Errorf("level %q accepted", s)
		}
	}
	for _, s := range []string{"on", "paused", "off"} {
		if _, ok := ParseSharingState(s); !ok {
			t.Errorf("state %q rejected", s)
		}
	}
	if _, ok := ParseSharingState("live"); ok {
		t.Error("state live accepted")
	}
	for _, s := range []string{"user_ok", "false_alarm", "handled"} {
		if _, ok := ParseResolution(s); !ok {
			t.Errorf("resolution %q rejected", s)
		}
	}
	if _, ok := ParseResolution("ok"); ok {
		t.Error("resolution ok accepted")
	}
	if DefaultLevel != LevelEmergency {
		t.Error("SPEC default grant must be emergency")
	}
}

func TestCanResolve(t *testing.T) {
	cases := []struct {
		owner bool
		r     Resolution
		want  bool
	}{
		{true, ResolutionUserOK, true},
		{true, ResolutionFalseAlarm, true},
		{true, ResolutionHandled, true},
		{false, ResolutionHandled, true},
		{false, ResolutionUserOK, false},
		{false, ResolutionFalseAlarm, false},
	}
	for _, c := range cases {
		if got := CanResolve(c.owner, c.r); got != c.want {
			t.Errorf("CanResolve(%v,%s)=%v want %v", c.owner, c.r, got, c.want)
		}
	}
	if StatusFor(ResolutionFalseAlarm) != "cancelled" || StatusFor(ResolutionUserOK) != "resolved" || StatusFor(ResolutionHandled) != "resolved" {
		t.Error("status mapping")
	}
}
