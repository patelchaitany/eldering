package handlers

import (
	"math"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// Clock-skew allowance for client timestamps.
const (
	maxFutureSkew = 5 * time.Minute
	maxPastAge    = 7 * 24 * time.Hour
)

func validLatLng(lat, lng float64) bool {
	return !math.IsNaN(lat) && !math.IsNaN(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
}

// checkTimestamp rejects client times far in the future or absurdly old.
func checkTimestamp(t time.Time, now time.Time, field string) error {
	if t.IsZero() {
		return badRequest("invalid_"+field, field+" is required")
	}
	if t.After(now.Add(maxFutureSkew)) {
		return badRequest("invalid_"+field, field+" is in the future")
	}
	if t.Before(now.Add(-maxPastAge)) {
		return badRequest("invalid_"+field, field+" is too old")
	}
	return nil
}

func optNonNeg(v *float64, max float64, field string) (*float32, error) {
	if v == nil {
		return nil, nil
	}
	if math.IsNaN(*v) || *v < 0 || *v > max {
		return nil, badRequest("invalid_"+field, field+" is out of range")
	}
	f := float32(*v)
	return &f, nil
}

var activities = map[string]bool{"still": true, "walking": true, "running": true, "cycling": true, "vehicle": true, "unknown": true}

// normalizeActivity accepts the detection Mode names case-insensitively.
func normalizeActivity(a *string) (*string, error) {
	if a == nil {
		return nil, nil
	}
	v := strings.ToLower(strings.TrimSpace(*a))
	if v == "" {
		return nil, nil
	}
	if !activities[v] {
		return nil, badRequest("invalid_activity", "activity must be one of still, walking, running, cycling, vehicle, unknown")
	}
	return &v, nil
}

// cleanLandmark trims a reverse-geocoded label; empty means none.
func cleanLandmark(s *string) (*string, error) {
	if s == nil {
		return nil, nil
	}
	v := strings.TrimSpace(*s)
	if v == "" {
		return nil, nil
	}
	if !utf8.ValidString(v) || utf8.RuneCountInString(v) > 200 {
		return nil, badRequest("invalid_landmark", "landmark must be at most 200 characters")
	}
	v = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, v)
	return &v, nil
}
