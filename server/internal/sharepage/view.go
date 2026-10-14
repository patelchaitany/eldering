package sharepage

import (
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"math"
	"net/url"
	"time"
)

type pageModel struct {
	View     *pageView // nil => invalid/expired link or error
	Status   int
	MapLibre string // base path of the vendored MapLibre build
}

// pageView is pre-formatted so the template stays logic-free.
type pageView struct {
	KindLabel   string
	Active      bool
	StatusLabel string
	DetectedAt  time.Time
	DetectedAgo string // "(12 min ago)" at render time: readable without JS in any time zone
	ExpiresAt   time.Time

	HasLatest      bool
	LatestCoords   string
	LatestAccuracy string
	LatestAt       time.Time
	LatestAgo      string
	OSMURL         string
	GeoURL         template.URL // geo: is not on html/template's URL allow-list; built from floats only

	Landmarks []landmarkView
}

type landmarkView struct {
	At   time.Time
	Name string
}

var kindLabels = map[string]string{
	"vehicle_crash":     "Possible vehicle crash",
	"pedestrian_impact": "Possible pedestrian accident",
	"fall":              "Possible fall",
	"sos":               "SOS alert",
}

var resolutionLabels = map[string]string{
	"user_ok":     "They reported that they are OK.",
	"false_alarm": "It was cancelled as a false alarm.",
	"handled":     "A family member marked it as handled.",
}

func newPageView(d *Data) *pageView {
	v := &pageView{
		KindLabel:  kindLabels[d.Kind],
		Active:     d.Status == "active",
		DetectedAt: d.DetectedAt,
		ExpiresAt:  d.ShareExpiresAt,
	}
	if v.Active {
		v.DetectedAgo = agoLabel(d.DetectedAt, d.ServerTime)
	}
	if v.KindLabel == "" {
		v.KindLabel = "Emergency alert"
	}
	if v.Active {
		v.StatusLabel = "Active: this person may need help."
	} else {
		v.StatusLabel = "This alert has been resolved."
		if d.Resolution != nil {
			if l, ok := resolutionLabels[*d.Resolution]; ok {
				v.StatusLabel += " " + l
			}
		}
	}
	if p := d.Latest; p != nil {
		v.HasLatest = true
		v.LatestCoords = fmt.Sprintf("%.6f, %.6f", p.Lat, p.Lng)
		if p.AccuracyM != nil {
			v.LatestAccuracy = fmt.Sprintf("±%.0f m", *p.AccuracyM)
		}
		v.LatestAt = p.RecordedAt
		v.LatestAgo = agoLabel(p.RecordedAt, d.ServerTime)
		v.OSMURL = osmURL(p.Lat, p.Lng)
		v.GeoURL = template.URL(fmt.Sprintf("geo:%.6f,%.6f", p.Lat, p.Lng))
	}
	for _, p := range d.Trail {
		if p.Landmark != nil {
			v.Landmarks = append(v.Landmarks, landmarkView{At: p.RecordedAt, Name: *p.Landmark})
		}
	}
	return v
}

// agoLabel mirrors ago() in share.js, so the no-JS page (whose absolute times
// are in UTC: the server cannot know the viewer's time zone) still says how
// recent a time is. share.js localizes the times and keeps these labels fresh.
func agoLabel(t, now time.Time) string {
	if t.IsZero() || now.IsZero() {
		return ""
	}
	s := int64(math.Round(max(0, now.Sub(t).Seconds())))
	if s < 90 {
		return fmt.Sprintf("(%d s ago)", s)
	}
	m := int64(math.Round(float64(s) / 60))
	if m < 90 {
		return fmt.Sprintf("(%d min ago)", m)
	}
	return fmt.Sprintf("(%d h ago)", int64(math.Round(float64(m)/60)))
}

func osmURL(lat, lng float64) string {
	q := url.Values{}
	q.Set("mlat", fmt.Sprintf("%.6f", lat))
	q.Set("mlon", fmt.Sprintf("%.6f", lng))
	return "https://www.openstreetmap.org/?" + q.Encode() + fmt.Sprintf("#map=17/%.6f/%.6f", lat, lng)
}

func jsonEncode(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(true)
	return enc.Encode(v)
}
