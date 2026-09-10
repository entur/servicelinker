FROM gcr.io/distroless/java25-debian13:nonroot

WORKDIR /app
COPY build/libs/app.jar app.jar

EXPOSE 8080
# Distroless has no shell, so exec form only. The base image sets ENTRYPOINT ["java", "-jar"],
# which would swallow the JVM flags, so override it with the full command.
# --enable-native-access: grpc-netty-shaded uses Unsafe/native on JDK 25; grant it to silence the startup warnings.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=65.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "--enable-native-access=ALL-UNNAMED", \
  "-jar", "app.jar"]
