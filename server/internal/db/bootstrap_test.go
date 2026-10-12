package db

import (
	"regexp"
	"testing"
)

func TestScramVerifierFormat(t *testing.T) {
	v, err := ScramVerifier("0123456789abcdef0123456789abcdef")
	if err != nil {
		t.Fatal(err)
	}
	re := regexp.MustCompile(`^SCRAM-SHA-256\$4096:[A-Za-z0-9+/]{22}==\$[A-Za-z0-9+/]{43}=:[A-Za-z0-9+/]{43}=$`)
	if !re.MatchString(v) {
		t.Fatalf("unexpected verifier format: %s", v)
	}
	v2, _ := ScramVerifier("0123456789abcdef0123456789abcdef")
	if v == v2 {
		t.Fatal("salt must be random")
	}
	if _, err := ScramVerifier(""); err == nil {
		t.Fatal("empty password accepted")
	}
}
