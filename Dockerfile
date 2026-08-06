FROM registry.access.redhat.com/ubi9/openjdk-17:latest as builder

USER 0
WORKDIR /build

COPY pom.xml .

RUN mvn dependency:go-offline -q || true

COPY src/ src/

RUN mvn package -DskipTests -q

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest

USER 0
WORKDIR /addon

COPY --from=builder /build/target/java-analyzer-provider-1.0.0-SNAPSHOT.jar /usr/local/bin/java-provider.jar

RUN chgrp -R 0 /addon && chmod -R g=u /addon
USER 1001

ENV HOME=/addon
EXPOSE 14651

ENTRYPOINT ["java", "-jar", "/usr/local/bin/java-provider.jar", "--port", "14651"]
