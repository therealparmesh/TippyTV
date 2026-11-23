package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// A stale tab, a foreign origin, or a second tap must never submit another profile.
func TestSingleUse(t *testing.T) {
	const origin = "https://tv.example:1234"
	result := make(chan submission, 2)
	h := setupHandler(origin, "test-key", result)
	request := func(originHeader, token, body string) int {
		r := httptest.NewRequest(http.MethodPost, origin+"/finish", strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", originHeader)
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	const form = `{"method":"xtream","address":"https://provider.example","name":"Home","user":"me","password":"example"}`
	if request("https://other.example", "test-key", form) != 403 || request(origin, "old", form) != 403 || request(origin, "test-key", `{"method":"xtream"}`) != 400 {
		t.Fatal("accepted invalid submission")
	}
	var wg sync.WaitGroup
	codes := make(chan int, 2)
	for range 2 {
		wg.Go(func() { codes <- request(origin, "test-key", form) })
	}
	wg.Wait()
	seen := map[int]int{}
	seen[<-codes]++
	seen[<-codes]++
	if seen[204] != 1 || seen[410] != 1 || len(result) != 1 || (<-result).Method != "xtream" {
		t.Fatal("expected exactly one completed submission", seen)
	}
}
