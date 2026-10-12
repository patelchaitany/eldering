package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

// OpaqueTokenLen is the encoded length of a 32-byte base64url (unpadded) token.
const OpaqueTokenLen = 43

// NewOpaqueToken returns a random token (32 bytes from crypto/rand, base64url,
// unpadded) and its SHA-256, which is the only thing ever stored.
func NewOpaqueToken() (token string, hash []byte, err error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", nil, err
	}
	token = base64.RawURLEncoding.EncodeToString(raw)
	return token, HashToken(token), nil
}

// HashToken is the lookup key for a presented token. Hashing the presented
// string (not its decoded bytes) means malformed input simply never matches.
func HashToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// LooksLikeOpaqueToken is a cheap pre-check before touching the database.
func LooksLikeOpaqueToken(s string) bool {
	if len(s) != OpaqueTokenLen {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}
