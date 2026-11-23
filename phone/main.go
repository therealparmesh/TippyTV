// The Android activity owns this process. Only its private stdout pipe carries the submission.
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"crypto/tls"
	"embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime"
	"net"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"go.lucor.dev/lancert-cli/internal/cli"
)

//go:embed index.html setup.js dracula.css style.css Dracula-LICENSE.txt
var assets embed.FS

type submission struct {
	Method   string `json:"method"`
	Name     string `json:"name"`
	Address  string `json:"address"`
	User     string `json:"user"`
	Password string `json:"password"`
	Guide    string `json:"guide"`
}

func setupHandler(origin, token string, result chan<- submission) http.Handler {
	var used atomic.Bool
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if "https://"+r.Host != origin {
			http.Error(w, "Unknown host", 421)
			return
		}
		if r.Method == "GET" {
			file := strings.TrimPrefix(r.URL.Path, "/")
			if file == "" {
				file = "index.html"
			}
			data, err := assets.ReadFile(file)
			if err != nil {
				http.NotFound(w, r)
				return
			}
			w.Header().Set("Content-Type", map[string]string{"index.html": "text/html; charset=utf-8", "setup.js": "text/javascript", "style.css": "text/css", "dracula.css": "text/css", "Dracula-LICENSE.txt": "text/plain"}[file])
			w.Write(data)
			return
		}
		if r.Method != "POST" || r.URL.Path != "/finish" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Origin") != origin || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+token)) != 1 {
			http.Error(w, "Scan the code on the TV again.", 403)
			return
		}
		kind, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if kind != "application/json" {
			http.Error(w, "Invalid form", 415)
			return
		}
		var form submission
		dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384))
		dec.DisallowUnknownFields()
		if dec.Decode(&form) != nil || dec.Decode(&struct{}{}) != io.EOF {
			http.Error(w, "Invalid form", 400)
			return
		}
		switch form.Method {
		case "cancel":
			form = submission{Method: "cancel"}
		case "quick", "jellyfin", "xtream", "playlist":
			if strings.TrimSpace(form.Address) == "" || ((form.Method == "xtream" || form.Method == "playlist") && strings.TrimSpace(form.Name) == "") || ((form.Method == "jellyfin" || form.Method == "xtream") && strings.TrimSpace(form.User) == "") || (form.Method == "xtream" && form.Password == "") {
				http.Error(w, "Complete the required fields.", 400)
				return
			}
		default:
			http.Error(w, "Unknown sign-in method", 400)
			return
		}
		if !used.CompareAndSwap(false, true) {
			http.Error(w, "Setup already closed.", 410)
			return
		}
		w.WriteHeader(http.StatusNoContent)
		result <- form
	})
}

func run() error {
	if len(os.Args) != 4 {
		return fmt.Errorf("missing network configuration")
	}
	ip := net.ParseIP(os.Args[1])
	if ip == nil || !ip.IsPrivate() {
		return fmt.Errorf("private network required")
	}
	// A lost parent must release the socket even during issuance.
	go func() { io.Copy(io.Discard, os.Stdin); os.Exit(0) }()
	var details bytes.Buffer
	args := []string{"lancert", "--config-dir", os.Args[2], "--accept-terms", "--dns-server", net.JoinHostPort(os.Args[3], "53"), ip.String()}
	if err := cli.Run(args, strings.NewReader(""), &details, io.Discard); err != nil {
		return err
	}
	fields := map[string]string{}
	for _, line := range strings.Split(details.String(), "\n") {
		k, v, ok := strings.Cut(line, ": ")
		if ok {
			fields[k] = v
		}
	}
	host := fields["Hostname"]
	if !strings.HasSuffix(host, ".lancert.dev") {
		return fmt.Errorf("invalid host")
	}
	cert, err := tls.LoadX509KeyPair(fields["Certificate"], fields["Private key"])
	if err != nil {
		return err
	}
	ln, err := net.Listen("tcp", net.JoinHostPort(ip.String(), "0"))
	if err != nil {
		return err
	}
	defer ln.Close()
	origin := "https://" + net.JoinHostPort(host, fmt.Sprint(ln.Addr().(*net.TCPAddr).Port))
	nonce := make([]byte, 32)
	if _, err = rand.Read(nonce); err != nil {
		return err
	}
	token := base64.RawURLEncoding.EncodeToString(nonce)
	result := make(chan submission, 1)
	server := http.Server{Handler: setupHandler(origin, token, result), ReadHeaderTimeout: 4 * time.Second, ReadTimeout: 8 * time.Second, WriteTimeout: 8 * time.Second, IdleTimeout: 6 * time.Second, MaxHeaderBytes: 8192, ErrorLog: log.New(io.Discard, "", 0), TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}}
	stopped := make(chan error, 1)
	go func() { stopped <- server.ServeTLS(ln, "", "") }()
	output := json.NewEncoder(os.Stdout)
	if err = output.Encode(map[string]string{"url": origin + "/#" + token}); err != nil {
		return err
	}
	select {
	case form := <-result:
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			server.Close()
		}
		return output.Encode(form)
	case <-time.After(15 * time.Minute):
		server.Close()
		return output.Encode(map[string]string{"method": "expired"})
	case err := <-stopped:
		return err
	}
}

func main() {
	if run() != nil {
		os.Exit(1)
	}
}
