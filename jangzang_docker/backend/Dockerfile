FROM docker.io/library/eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 AS java

FROM docker.io/library/nginx:1.30-alpine@sha256:901e593c95dc85f5a729f5765516321366ef4ac401821431d408897e95287d0a

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=java /opt/java/openjdk/ /opt/java/openjdk/
COPY platform-api.jar /app/platform-api.jar
COPY default.conf /etc/nginx/conf.d/default.conf
COPY --chmod=0755 start.sh /usr/local/bin/start-platform

ENV API_BIND_ADDRESS=127.0.0.1 API_PORT=8081

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/start-platform"]
CMD []
