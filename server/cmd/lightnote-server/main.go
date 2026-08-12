package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"lightnote/server/internal/api"
	"lightnote/server/internal/auth"
	"lightnote/server/internal/blob"
	"lightnote/server/internal/config"
	"lightnote/server/internal/db"
	"lightnote/server/internal/sync"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	if cfg.JWTSecret == "" {
		secret, err := auth.RandomSecret()
		if err != nil {
			log.Fatalf("generate jwt secret: %v", err)
		}
		cfg.JWTSecret = secret
		log.Printf("WARNING: LIGHTNOTE_JWT_SECRET 未配置，已生成随机密钥（重启后已签发 Token 将失效）")
	}
	store, err := db.Open(cfg.DBPath)
	if err != nil {
		log.Fatalf("open database: %v", err)
	}
	defer store.Close()

	blobs, err := blob.NewStore(cfg.BlobDir)
	if err != nil {
		log.Fatalf("open blob store: %v", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := db.Migrate(ctx, store); err != nil {
		log.Fatalf("migrate database: %v", err)
	}
	a := auth.New(store, cfg.JWTSecret, cfg.TokenTTL)
	if _, err := a.EnsureDefaultUser(ctx, cfg.Username, cfg.Password); err != nil {
		log.Fatalf("ensure default user: %v", err)
	}

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api.New(cfg, store, a, sync.NewPushService(store), sync.NewPuller(store), blobs).Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       120 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	go func() {
		log.Printf("lightnote-server listening on %s", cfg.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server error: %v", err)
		}
	}()
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("shutdown: %v", err)
	}
}
