FROM scratch

ARG SOURCE_VERSION
ARG SOURCE_REVISION

LABEL org.opencontainers.image.title="Kubernetes kube-state-metrics" \
      org.opencontainers.image.source="https://github.com/kubernetes/kube-state-metrics" \
      org.opencontainers.image.version="${SOURCE_VERSION}" \
      org.opencontainers.image.revision="${SOURCE_REVISION}"

COPY bin/kube-state-metrics /kube-state-metrics
COPY LICENSE /LICENSE

USER 65534:65534
EXPOSE 8080 8081
ENTRYPOINT ["/kube-state-metrics"]
