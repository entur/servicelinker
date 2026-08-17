FROM bellsoft/liberica-runtime-container:jre-25-musl

# hadolint ignore=DL3018
RUN apk upgrade --no-cache

WORKDIR /srv
COPY build/libs/*.jar app.jar
RUN chown -R 1000:1000 /srv
USER 1000:1000

EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=65.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "./app.jar"]
