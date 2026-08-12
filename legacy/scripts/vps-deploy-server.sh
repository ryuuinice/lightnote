#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="${SOURCE_DIR:-/git-workspace/lightnote-dev}"
APP_DIR="${APP_DIR:-/git-workspace/lightnote}"
BRANCH="${BRANCH:-master}"
SERVER_PORT="${SERVER_PORT:-8080}"
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-17}"
SKIP_PULL="${SKIP_PULL:-false}"
SKIP_TESTS="${SKIP_TESTS:-false}"

log() {
  printf '[LightNote Deploy] %s\n' "$*"
}

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    tr -dc 'a-f0-9' </dev/urandom | head -c 48
  fi
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '缺少命令: %s\n' "$1" >&2
    exit 1
  fi
}

require_command docker
require_command git

if [ ! -d "$SOURCE_DIR/.git" ]; then
  printf '源码目录不存在或不是 Git 仓库: %s\n' "$SOURCE_DIR" >&2
  exit 1
fi

if [ "$SKIP_PULL" != "true" ]; then
  log "更新源码: $SOURCE_DIR ($BRANCH)"
  git -C "$SOURCE_DIR" fetch --all --prune
  git -C "$SOURCE_DIR" checkout "$BRANCH"
  git -C "$SOURCE_DIR" pull --ff-only
fi

mkdir -p "$APP_DIR/server" "$APP_DIR/logs" "$APP_DIR/initdb" "$APP_DIR/data/mysql"

if [ ! -f "$APP_DIR/.env" ]; then
  MYSQL_ROOT_PASSWORD="$(random_hex)"
  MYSQL_PASSWORD="$(random_hex)"
  LIGHTNOTE_JWT_SECRET="$(random_hex)$(random_hex)"
  cat >"$APP_DIR/.env" <<EOF
MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD
MYSQL_DATABASE=lightnote
MYSQL_USER=lightnote
MYSQL_PASSWORD=$MYSQL_PASSWORD
LIGHTNOTE_DB_URL=jdbc:mysql://mysql:3306/lightnote?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
LIGHTNOTE_DB_USERNAME=lightnote
LIGHTNOTE_DB_PASSWORD=$MYSQL_PASSWORD
LIGHTNOTE_DB_DRIVER=com.mysql.cj.jdbc.Driver
LIGHTNOTE_SERVER_PORT=8080
LIGHTNOTE_JWT_SECRET=$LIGHTNOTE_JWT_SECRET
LIGHTNOTE_JWT_EXPIRE_SECONDS=7200
EOF
  chmod 600 "$APP_DIR/.env"
  log "已生成环境配置: $APP_DIR/.env"
fi

cat >"$APP_DIR/docker-compose.yml" <<EOF
services:
  mysql:
    image: mysql:8.0
    container_name: lightnote-mysql
    restart: unless-stopped
    env_file:
      - ./.env
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-authentication-plugin=mysql_native_password
    volumes:
      - ./data/mysql:/var/lib/mysql
      - ./initdb:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p\$\$MYSQL_ROOT_PASSWORD --silent"]
      interval: 10s
      timeout: 5s
      retries: 12

  server:
    image: eclipse-temurin:17-jre
    container_name: lightnote-server
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
    env_file:
      - ./.env
    volumes:
      - ./server:/app
      - ./logs:/logs
    working_dir: /app
    command: ["java", "-jar", "lightnote-server.jar"]
    ports:
      - "${SERVER_PORT}:8080"
EOF

cp "$SOURCE_DIR/docs/db.sql" "$APP_DIR/initdb/001-init.sql"

log "使用 Docker 内 Maven 构建服务端"
MVN_ARGS=("mvn")
if [ "$SKIP_TESTS" = "true" ]; then
  MVN_ARGS+=("-DskipTests")
fi
MVN_ARGS+=("package")

docker run --rm \
  -v "$SOURCE_DIR:/workspace" \
  -v "$HOME/.m2:/root/.m2" \
  -w /workspace/lightnote-server \
  "$MAVEN_IMAGE" \
  "${MVN_ARGS[@]}"

JAR_PATH="$SOURCE_DIR/lightnote-server/target/lightnote-server-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR_PATH" ]; then
  printf '未找到服务端 Jar: %s\n' "$JAR_PATH" >&2
  exit 1
fi
cp "$JAR_PATH" "$APP_DIR/server/lightnote-server.jar"

log "启动服务: $APP_DIR"
docker compose -f "$APP_DIR/docker-compose.yml" --env-file "$APP_DIR/.env" --project-directory "$APP_DIR" up -d

log "等待健康检查"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${SERVER_PORT}/api/health" >/tmp/lightnote-health.json 2>/dev/null; then
    cat /tmp/lightnote-health.json
    printf '\n'
    log "部署完成: http://127.0.0.1:${SERVER_PORT}/api/health"
    exit 0
  fi
  sleep 2
done

docker logs --tail 120 lightnote-server || true
printf '健康检查超时，请查看容器日志。\n' >&2
exit 1
