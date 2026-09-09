FROM gcr.io/distroless/java25-debian13:nonroot

WORKDIR /app
COPY build/libs/app.jar app.jar

EXPOSE 8080
# Distroless has no shell, so exec form only. The base image sets ENTRYPOINT ["java", "-jar"],
# which would swallow the JVM flags, so override it with the full command.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=65.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
