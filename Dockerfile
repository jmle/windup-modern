FROM registry.access.redhat.com/ubi9/openjdk-17:latest as builder

USER 0
WORKDIR /build

COPY pom.xml .
COPY windup-bom/pom.xml windup-bom/pom.xml
COPY windup-grpc/pom.xml windup-grpc/pom.xml

RUN mvn dependency:go-offline -pl windup-grpc -am -q || true

COPY windup-bom/ windup-bom/
COPY windup-grpc/ windup-grpc/

RUN mvn package -pl windup-grpc -am -DskipTests -q

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest

USER 0
WORKDIR /addon

COPY --from=builder /build/windup-grpc/target/windup-grpc-7.0.0-SNAPSHOT.jar /usr/local/bin/java-provider.jar

RUN chgrp -R 0 /addon && chmod -R g=u /addon
USER 1001

ENV HOME=/addon
EXPOSE 14651

ENTRYPOINT ["java", "-jar", "/usr/local/bin/java-provider.jar", "--port", "14651"]
