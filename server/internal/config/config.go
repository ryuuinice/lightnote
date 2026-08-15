package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Addr        string
	DBPath      string
	BlobDir     string
	JWTSecret   string
	TokenTTL    time.Duration
	Username    string
	Password    string
	PasswordSet bool // 密码是否经 config 文件或环境变量显式设置
}

type fileConfig struct {
	Server struct {
		Addr string `yaml:"addr"`
	} `yaml:"server"`
	Database struct {
		Path string `yaml:"path"`
	} `yaml:"database"`
	Auth struct {
		JWTSecret      string `yaml:"jwt_secret"`
		TokenTTLHours  int    `yaml:"token_ttl_hours"`
	} `yaml:"auth"`
	User struct {
		Username string `yaml:"username"`
		Password string `yaml:"password"`
	} `yaml:"user"`
}

func Load() (*Config, error) {
	c := &Config{
		Addr:      ":8080",
		DBPath:    "lightnote.db",
		TokenTTL:  2 * time.Hour,
		Username:  "admin",
		Password:  "admin123",
	}
	path := os.Getenv("LIGHTNOTE_CONFIG")
	if path == "" {
		path = "config.yaml"
	}
	if b, err := os.ReadFile(path); err == nil {
		var fc fileConfig
		if err := yaml.Unmarshal(b, &fc); err != nil {
			return nil, fmt.Errorf("parse config %s: %w", path, err)
		}
		if fc.Server.Addr != "" {
			c.Addr = fc.Server.Addr
		}
		if fc.Database.Path != "" {
			c.DBPath = fc.Database.Path
		}
		if fc.Auth.JWTSecret != "" {
			c.JWTSecret = fc.Auth.JWTSecret
		}
		if fc.Auth.TokenTTLHours > 0 {
			c.TokenTTL = time.Duration(fc.Auth.TokenTTLHours) * time.Hour
		}
		if fc.User.Username != "" {
			c.Username = fc.User.Username
		}
		if fc.User.Password != "" {
			c.Password = fc.User.Password
			c.PasswordSet = true
		}
	} else if path != "config.yaml" {
		return nil, fmt.Errorf("read config %s: %w", path, err)
	}
	if v := os.Getenv("LIGHTNOTE_ADDR"); v != "" {
		c.Addr = v
	}
	if v := os.Getenv("LIGHTNOTE_DB_PATH"); v != "" {
		c.DBPath = v
	}
	if v := os.Getenv("LIGHTNOTE_BLOB_DIR"); v != "" {
		c.BlobDir = v
	}
	if c.BlobDir == "" {
		if dir := filepath.Dir(c.DBPath); dir != "" && dir != "." {
			c.BlobDir = filepath.Join(dir, "blobs")
		} else {
			c.BlobDir = "blobs"
		}
	}
	if v := os.Getenv("LIGHTNOTE_JWT_SECRET"); v != "" {
		c.JWTSecret = v
	}
	if v := os.Getenv("LIGHTNOTE_USERNAME"); v != "" {
		c.Username = v
	}
	if v := os.Getenv("LIGHTNOTE_PASSWORD"); v != "" {
		c.Password = v
		c.PasswordSet = true
	}
	if v := os.Getenv("LIGHTNOTE_TOKEN_TTL_HOURS"); v != "" {
		h, err := strconv.Atoi(v)
		if err != nil || h <= 0 {
			return nil, fmt.Errorf("invalid LIGHTNOTE_TOKEN_TTL_HOURS %q", v)
		}
		c.TokenTTL = time.Duration(h) * time.Hour
	}
	return c, nil
}
