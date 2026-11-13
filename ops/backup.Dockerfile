FROM postgres:16-alpine

ARG ALPINE_MIRROR=dl-cdn.alpinelinux.org/alpine

COPY ops/.build/age /usr/local/bin/age
RUN sed -i "s#dl-cdn.alpinelinux.org/alpine#${ALPINE_MIRROR}#g" /etc/apk/repositories \
    && apk upgrade --no-cache \
    && rm -f /usr/local/bin/gosu \
    && chmod 0555 /usr/local/bin/age \
    && addgroup -S -g 10001 agent \
    && adduser -S -D -H -u 10001 -G agent agent

WORKDIR /opt/myai
COPY ops/postgres-backup.sh /opt/myai/postgres-backup.sh
COPY ops/run-scheduled-backup.sh /opt/myai/run-scheduled-backup.sh
COPY ops/prune-postgres-backups.sh /opt/myai/prune-postgres-backups.sh
RUN chmod 0555 /opt/myai/postgres-backup.sh /opt/myai/run-scheduled-backup.sh /opt/myai/prune-postgres-backups.sh

USER 10001:10001
ENTRYPOINT ["/opt/myai/run-scheduled-backup.sh"]
