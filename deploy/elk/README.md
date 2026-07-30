# dev ELK 운영 runbook

dev logging은 WAS의 Filebeat와 private ELK host의 Elasticsearch/Kibana로 구성한다. 현재 자산은
Filebeat `container` input을 사용하는 Elastic `8.19.18` 전용이다. Elasticsearch, setup, Kibana와
Filebeat version을 함께 유지하고 9.x로 올릴 때는 filestream parser migration을 별도 작업으로 한다.

```bash
cp deploy/elk/.env.example deploy/elk/.env
# hidden input으로 ELASTIC_PASSWORD, KIBANA_PASSWORD, FILEBEAT_PASSWORD 추가
chmod 600 deploy/elk/.env
docker compose --env-file deploy/elk/.env -f deploy/elk/docker-compose.yml config --quiet
```

`.env`와 password는 Git, S3 bootstrap, SSM command argument에 넣지 않는다. ELK host의 9200과
5601은 WAS SG에서만 허용하고, public 접속은 WAS nginx TLS의 `/kibana`에서 끝낸다.

AWS/host 변경 승인을 받은 뒤 `bootstrap/elk/`의 compose, ILM, index template을 exact key로
내려받고 root-only `.env`를 준비한다.

```bash
cd /opt/laimory-elk
sudo docker compose --env-file .env config --quiet
sudo docker compose --env-file .env up -d
sudo docker compose --env-file .env ps
```

setup container가 Elasticsearch yellow 이상을 기다린 뒤 Kibana system password, ILM, index
template과 Filebeat writer를 멱등 설정한다. WAS에서는 `bootstrap/elk/filebeat.yml`을 내려받아
Filebeat password를 runtime env로만 주입하고 Docker log directory를 read-only mount한다. Kibana
location은 기존 nginx extra include를 보존하며 `/kibana`에 추가한다.

검증은 Elasticsearch health, setup exit 0, Kibana login, Filebeat output, 새 dev request의
transaction ID 검색 순서로 한다. rollback은 stack을 내리고 이전 compose/ILM/template과 이전
`.env` backup을 복원해 다시 올린다. Filebeat 또는 nginx만 실패하면 해당 WAS 구성만 이전 버전으로
복원한다. volume을 삭제하는 `down -v`는 data 삭제 승인을 별도로 받지 않는 한 실행하지 않는다.
