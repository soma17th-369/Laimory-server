# OCI prod 무중단 배포 (api·ai blue/green)

`main` 머지 → GitHub arm64 runner 빌드 → GHCR 공개 이미지 → Tailscale SSH → host의 `laimory-deploy`가 교체한다.
AI 서버(Laimory-AI)의 workflow도 같은 host 스크립트를 쓴다.

| 파일 | host 경로 | 역할 |
|---|---|---|
| `laimory-deploy` | `/usr/local/sbin/` (0755 root) | 배포·복귀 본체. systemd unit(`laimory-deploy-api`·`-ai`)으로 분리 실행 |
| `laimory-deploy-gate` | `/usr/local/bin/` (0755 root) | `deploy` 계정의 SSH forced command. `deploy <sha> <digest>`만 통과 |

host 파일은 자동 반영하지 않는다 — 바꾸면 머지된 커밋의 파일을 사람이 설치한다.

## 배포 순서

1. 시작 조건: upstream 파일이 활성 색 하나를 가리키고, 활성 색만 실행 중이고, compose 파일이 유효하다. 아니면 아무것도 하지 않는다.
2. 이미지를 digest로 받고 `org.opencontainers.image.revision` label이 요청 SHA와 같은지 본다.
3. api만: KST 02:25~04:50, 20:55~21:10이면 중단한다(스케줄 job과 두 api 공존을 피한다). 지나서 다시 실행한다.
4. 대기 색 container를 새 이미지로 띄우고, 실제로 뜬 이미지가 2단계에서 확인한 이미지인지 대조한다. Flyway migration은 이때 실행된다. api는 `/readyz`·`/api/v1/intro`, ai는 `/ping`이
   90초 안에 정상이어야 한다.
5. nginx upstream의 `down` 표시를 옮기고 reload한다. 내부 listener가 대기 색 주소로 연속 10회 응답해야 전환으로 본다.
6. 30초 뒤 옛 색을 `docker stop`(api 120초, ai 300초)한다. 삭제하지 않고 복귀용으로 둔다.

4단계까지의 실패는 트래픽에 영향이 없고 대기 색만 정지한다. 5단계 실패는 upstream을 되돌린 뒤 대기 색을 정지한다.
출력은 고정 문구·색·SHA·digest뿐이다(공개 Actions 로그). 원인은 host에서 `docker logs`·`journalctl -u laimory-deploy-api`로 본다.

## 복귀(rollback)

- 직전 배포가 성공했다: host에서 `sudo laimory-deploy rollback api`(또는 `ai`). 보관한 옛 색을 켜고 전환한다(무중단).
- 직전 배포가 실패했거나 더 이전 버전: `Deploy prod (OCI)`를 `main`에서 `action=deploy`, `image_sha=<이전 SHA>`로 실행한다.
  `image_sha`는 `main` 이력에 있는 커밋이어야 한다(다른 브랜치를 `build-only`로 빌드한 이미지를 실수로 올리는 것을 막는다. SHA만 보므로 이미지 내용까지 보장하지는 않는다).
  실패한 배포가 대기 색을 덮어써서 `rollback`은 거부된다.
- 앱 복귀는 Flyway가 적용한 DB 변경을 되돌리지 않는다. migration이 실패하면 활성 색은 계속 서빙하지만 **새 이미지는 복구
  전까지 다시 배포하지 않는다**([Flyway 절차 §5](../../../docs/database/flyway-adoption.md)).
- 시간 차단 중의 긴급 복귀도 `laimory-deploy rollback`이다(차단을 보지 않는다).

`CRITICAL`이 나오면 nginx 설정을 되돌리지 못해 두 색이 함께 떠 있다. host에서 upstream 파일과 `nginx -t`를 확인한다.
`... is still running`이면 트래픽은 새 색에 있으니 옛 색만 정지하면 된다.

## host 구조 계약

스크립트가 기대하는 구조다. compose·nginx 원본은 host가 소유한다.

`/opt/laimory/.env` (0644, 비밀 아님 — 스크립트가 갱신): `API_BLUE_IMAGE`·`API_BLUE_SHA`·`API_GREEN_*`·`AI_BLUE_*`·`AI_GREEN_*`.

`compose.yml` — `profiles`가 있어야 `docker compose up -d` 전체 실행이 정지한 색을 켜지 않는다. 다른 서비스의 `depends_on`에
두 색을 넣지 않는다. `container_name`·호스트 포트를 주지 않는다.

```yaml
  api-blue: &api
    image: ${API_BLUE_IMAGE:?}
    profiles: [api-blue]
    env_file: ./api.env
    environment:
      APP_COMMIT_SHA: ${API_BLUE_SHA:?}
      # 1792m 미만에서는 JVM이 SerialGC를 고르므로 G1을 명시한다
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=60 -XX:+UseG1GC"
    mem_limit: 1536m
    logging: {driver: json-file, options: {max-size: "100m", max-file: "10"}}
    volumes:
      - ./secrets/firebase-service-account.json:/run/secrets/firebase-service-account.json:ro
    restart: unless-stopped
    networks: [laimory]
  api-green:
    <<: *api
    image: ${API_GREEN_IMAGE:?}
    profiles: [api-green]
    environment:
      APP_COMMIT_SHA: ${API_GREEN_SHA:?}
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=60 -XX:+UseG1GC"
  ai-blue: &ai
    image: ${AI_BLUE_IMAGE:?}
    profiles: [ai-blue]
    env_file: ./ai.env
    environment:
      AGENT_VERSION: ${AI_BLUE_SHA:?}
    restart: unless-stopped
    networks: [laimory]
  ai-green:
    <<: *ai
    image: ${AI_GREEN_IMAGE:?}
    profiles: [ai-green]
    environment:
      AGENT_VERSION: ${AI_GREEN_SHA:?}
```

`environment`가 `env_file`보다 우선하므로 `api.env`의 `APP_COMMIT_SHA`는 쓰이지 않는다.

docker 네트워크 이름은 정확히 `laimory`여야 한다(스크립트가 이 이름으로 대기 색 주소를 읽는다). compose 최상위에
`networks: {laimory: {name: laimory}}`를 둔다 — 없으면 `laimory_laimory`가 되어 모든 배포가 전환 전에 실패한다.

`nginx/upstream/{api,ai}.conf` — 디렉터리째 `/etc/nginx/upstream:ro`로 마운트하고 템플릿이 `include /etc/nginx/upstream/*.conf;` 한다.
두 색을 늘 함께 적고 `down`만 옮긴다(새 이름이 처음 들어오는 reload는 주소를 몰라 그 순간 502가 난다).

```nginx
upstream laimory_api {
    zone laimory_api 64k;
    resolver_timeout 2s;
    server api-blue:8080 resolve max_fails=0;
    server api-green:8080 resolve max_fails=0 down;
}
```

공개 server 블록은 `proxy_pass http://laimory_api;`를 쓴다. 내부 listener 두 개는 호스트에 게시하지 않는다.

```nginx
# ai → api. 외부용 ModSecurity·요청 제한·본문 상한을 물려받으면 nginx의 4xx를 앱이 "거절 확정"으로 읽는다.
server {
    listen 8088;
    modsecurity off;
    client_max_body_size 0;
    keepalive_timeout 0;                                  # reload 때 닫힌 연결이 재사용되지 않게
    add_header X-Laimory-Upstream $upstream_addr always;  # 배포 스크립트가 전환을 확인한다
    location / {
        proxy_pass http://laimory_api;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host api:8080;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto http;
        proxy_connect_timeout 2s;
        proxy_read_timeout 60s;
    }
}
# api → ai (같은 정책, listen 8089, upstream laimory_ai, Host ai:8080)
```

`api.env`의 `APP_AI_HTTP_BASE_URL=http://nginx:8089`, `ai.env`의 `APP_SERVER_API_URL=http://nginx:8088`.
nginx를 재생성하면 이 경로도 잠시 끊기므로 그 전에 활성 ai의 `/ping`이 `Healthy`(처리 중 없음)인지 본다.

## 설치 (host, 사람)

```bash
sudo install -o root -g root -m 0755 laimory-deploy /usr/local/sbin/laimory-deploy
sudo install -o root -g root -m 0755 laimory-deploy-gate /usr/local/bin/laimory-deploy-gate
sudo install -d -o root -g root -m 0755 /var/lib/laimory/deploy
sudo useradd --system --create-home --home-dir /home/deploy --shell /bin/bash deploy && sudo passwd -l deploy
echo 'deploy ALL=(root) NOPASSWD: /usr/local/sbin/laimory-deploy ^(api|ai) [0-9a-f]{40} sha256\:[0-9a-f]{64}$' \
  | sudo tee /etc/sudoers.d/laimory-deploy >/dev/null
sudo chmod 0440 /etc/sudoers.d/laimory-deploy && sudo visudo -cf /etc/sudoers.d/laimory-deploy
```

`/home/deploy/.ssh/authorized_keys`(디렉터리와 파일 모두 root 소유, 0755/0644)에 저장소마다 다른 키를 한 줄씩 둔다. `deploy` 계정에
docker 그룹을 주지 않는다. Tailscale SSH는 꺼 둔다 — 켜면 authorized_keys의 forced command가 적용되지 않는다.

```text
restrict,command="/usr/local/bin/laimory-deploy-gate api" ssh-ed25519 <Laimory-server 공개키>
restrict,command="/usr/local/bin/laimory-deploy-gate ai" ssh-ed25519 <Laimory-AI 공개키>
```

## GitHub·Tailscale 설정

| 위치 | 이름 | 내용 |
|---|---|---|
| GitHub Environment `prod`(배포 브랜치 `main`만) Secrets | `OCI_DEPLOY_SSH_KEY`·`OCI_DEPLOY_HOST`·`OCI_DEPLOY_KNOWN_HOSTS` | host 주소도 Secret(공개 로그 마스킹) |
| 같은 Environment Variables | `TS_OAUTH_CLIENT_ID`·`TS_AUDIENCE` | federated identity 식별자(비밀 아님) |
| 저장소 Variable | `DEPLOY_PAUSED` | `true`면 push 빌드·배포를 건너뛴다(수동 실행은 통과) |
| Tailscale | federated identity | `auth_keys` write + `tag:ci`, subject = 저장소의 Environment `prod` |
| Tailscale ACL | grant·tests | `tag:ci` → OCI host `tcp:22`만 허용, 그 밖은 거부 test |
| GHCR | 패키지 공개 | 첫 push는 비공개로 생긴다. 공개 전환 뒤 host에서 익명 pull 확인. 되돌릴 수 없다 |

Laimory-AI 저장소도 같은 표를 따르되 값이 다르다. Environment는 `prod-oci`이고 Tailscale subject도 그 Environment
(`repo:soma17th-369/Laimory-AI:environment:prod-oci`)다. SSH 키는 위 `laimory-deploy-gate ai` 줄의 AI 전용 키를 쓰고(서버 키를 쓰면
host가 `laimory-server` 저장소에서 AI digest를 찾다 실패한다), GHCR 패키지는 `laimory-ai`를 따로 공개한다.

## 검증

```bash
bash .github/scripts/test-oci-deploy-contract.sh
```

2026-11-25 로컬 실측(nginx 1.30.6 같은 이미지, 연결 재사용·새 연결·8초 요청 혼합): 전환 중 실패 0건.
