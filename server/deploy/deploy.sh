#!/bin/sh
# Deploy the Eldering stack to a shared VM (host settings in deploy/deploy.env).
#
#   deploy/deploy.sh app      build linux/amd64 image, ship it, (re)start ~/eldering-safety
#   deploy/deploy.sh nginx    install the HTTP-only nginx site (nginx -t before reload)
#   deploy/deploy.sh cert     obtain the TLS certificate with certbot --nginx (this site only)
#   deploy/deploy.sh status   show our containers and the health endpoint
#   deploy/deploy.sh sites    read-only snapshot of every nginx site + containers + memory
#                             (run before and after a deploy and diff the two)
#
# Safety rules (server is shared): only touches ~/eldering-safety, the compose
# project "eldering", images named eldering-api, and /etc/nginx/sites-*/eldering.
# Secrets are generated on the server and never printed.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
# Host-specific settings live in deploy/deploy.env (not committed; see deploy.env.example).
# shellcheck disable=SC1091
[ -f "$HERE/deploy.env" ] && . "$HERE/deploy.env"
HOST=${ELDERING_HOST:?set ELDERING_HOST=user@server in deploy/deploy.env}
KEY=${ELDERING_SSH_KEY:?set ELDERING_SSH_KEY=/path/to/ssh/key in deploy/deploy.env}
DOMAIN=${ELDERING_DOMAIN:?set ELDERING_DOMAIN=api.example.com in deploy/deploy.env}
REMOTE_DIR=eldering-safety

ssh_() { ssh -i "$KEY" -o BatchMode=yes "$HOST" "$@"; }
scp_() { scp -i "$KEY" -o BatchMode=yes "$@"; }

cmd_app() {
	TAG=$(date -u +%Y%m%d%H%M%S)
	echo "==> building eldering-api:$TAG (linux/amd64)"
	docker buildx build --platform linux/amd64 --build-arg VERSION="$TAG" -t "eldering-api:$TAG" --load "$ROOT"

	echo "==> shipping image"
	docker save --platform linux/amd64 "eldering-api:$TAG" | gzip | ssh_ 'gunzip | docker load'

	echo "==> uploading compose file"
	ssh_ "mkdir -p ~/$REMOTE_DIR"
	scp_ "$ROOT/docker-compose.yml" "$HOST:$REMOTE_DIR/docker-compose.yml"

	echo "==> ensuring secrets exist (generated on the server, never echoed)"
	ssh_ "umask 077; cd ~/$REMOTE_DIR && if [ ! -f .env ]; then
	  printf 'POSTGRES_PASSWORD=%s\nELDERING_OWNER_PASSWORD=%s\nELDERING_APP_PASSWORD=%s\nJWT_SECRET=%s\nAPI_TAG=\nPUBLIC_BASE_URL=https://$DOMAIN\n' \
	    \"\$(openssl rand -hex 32)\" \"\$(openssl rand -hex 32)\" \"\$(openssl rand -hex 32)\" \"\$(openssl rand -hex 32)\" > .env
	fi; chmod 600 .env"

	echo "==> starting stack"
	ssh_ "set -e; cd ~/$REMOTE_DIR
	  if grep -q '^API_TAG=' .env; then sed -i 's/^API_TAG=.*/API_TAG=$TAG/' .env; else echo 'API_TAG=$TAG' >> .env; fi
	  docker compose -p eldering up -d --remove-orphans
	  i=0; until curl -fsS http://127.0.0.1:8095/healthz; do
	    i=\$((i+1)); if [ \$i -gt 60 ]; then docker compose -p eldering ps; docker compose -p eldering logs --tail 50 migrate api; exit 1; fi
	    sleep 3
	  done; echo
	  docker compose -p eldering ps"

	echo "==> pruning old eldering-api images (keeping 3)"
	ssh_ "docker images eldering-api --format '{{.Tag}}' | grep -E '^[0-9]{14}\$' | sort -r | tail -n +4 | xargs -r -I{} docker rmi eldering-api:{} >/dev/null 2>&1 || true"
}

cmd_nginx() {
	conf=$(mktemp)
	sed "s/__ELDERING_DOMAIN__/$DOMAIN/g" "$HERE/nginx/eldering.conf" > "$conf"
	scp_ "$conf" "$HOST:/tmp/eldering.nginx.conf"
	rm -f "$conf"
	ssh_ "set -e
	  if [ -f /etc/nginx/sites-available/eldering ] && grep -q 'managed by Certbot' /etc/nginx/sites-available/eldering; then
	    echo 'site already has a certificate; not overwriting (edit by hand)'; rm -f /tmp/eldering.nginx.conf; exit 0
	  fi
	  sudo install -m 644 /tmp/eldering.nginx.conf /etc/nginx/sites-available/eldering
	  rm -f /tmp/eldering.nginx.conf
	  sudo ln -sf /etc/nginx/sites-available/eldering /etc/nginx/sites-enabled/eldering
	  if sudo nginx -t; then sudo systemctl reload nginx; else
	    echo 'nginx -t failed: disabling the eldering site again'; sudo rm -f /etc/nginx/sites-enabled/eldering; exit 1
	  fi"
}

cmd_cert() {
	ssh_ "set -e
	  sudo certbot --nginx --non-interactive --redirect -d $DOMAIN
	  sudo nginx -t && sudo systemctl reload nginx"
	curl -fsS "https://$DOMAIN/healthz" && echo
}

cmd_status() {
	ssh_ "cd ~/$REMOTE_DIR && docker compose -p eldering ps && curl -fsS http://127.0.0.1:8095/healthz && echo"
}

# Read-only. Asks every server_name nginx serves (over loopback, with the right SNI/Host) for
# its status (https is certificate-verified: verify=0 means a valid certificate), plus the
# default servers, every container's state and free memory.
cmd_sites() {
	ssh_ 'sh -s' <<'EOF'
names=$(sudo nginx -T 2>/dev/null | sed -n 's/^[[:space:]]*server_name[[:space:]]\{1,\}\([^;]*\);.*/\1/p' |
  tr ' \t' '\n\n' | grep -vE '^(_|localhost|)$|[*~]' | sort -u)
for n in $names; do
  for s in http https; do
    p=80; [ "$s" = https ] && p=443
    r=$(curl -s -o /dev/null -m 10 --resolve "$n:$p:127.0.0.1" -w '%{http_code} verify=%{ssl_verify_result}' "$s://$n/" 2>/dev/null) || true
    [ "$s" = http ] && r=${r% verify=*}
    echo "$s://$n/ ${r:-000}"
  done
done
for u in http://127.0.0.1/ https://127.0.0.1/; do
  echo "$u (default server) $(curl -sk -o /dev/null -m 10 -w '%{http_code}' "$u" 2>/dev/null || true)"
done
echo '-- containers'
docker ps -a --format '{{.Names}} {{.State}} {{.Status}}' |
  sed -E 's/ [A-Z][a-z]+ .*\((healthy|unhealthy|health: starting)\).*$/ \1/; s/ (Up|Exited|Created|Restarting|Paused|Dead)( .*)?$//' | sort
echo '-- memory (MiB)'
free -m | awk '/^Mem:/ {print "mem total=" $2, "used=" $3, "available=" $7} /^Swap:/ {print "swap total=" $2, "used=" $3}'
EOF
}

case "${1:-}" in
	app) cmd_app ;;
	nginx) cmd_nginx ;;
	cert) cmd_cert ;;
	status) cmd_status ;;
	sites) cmd_sites ;;
	*) echo "usage: $0 app|nginx|cert|status|sites" >&2; exit 2 ;;
esac
