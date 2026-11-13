FROM ubuntu:26.04@sha256:065aa8626841f16057b78b027b464cd59c4fac36c204c72dcbf48d66f5fa8a96

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends squid \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade --yes \
    && groupadd --system --gid 10001 squid \
    && useradd --system --uid 10001 --gid squid --no-create-home --shell /usr/sbin/nologin squid \
    && mkdir -p /tmp \
    && rm -rf /var/lib/apt/lists/*

COPY ops/squid-model-egress.conf /etc/squid/myai-squid.conf
RUN /usr/sbin/squid -k parse -f /etc/squid/myai-squid.conf

USER 10001:10001
EXPOSE 3128
ENTRYPOINT ["/usr/sbin/squid", "-N", "-f", "/etc/squid/myai-squid.conf"]
