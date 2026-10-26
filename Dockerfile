FROM gcr.io/distroless/java25-debian13:nonroot@sha256:5d3177252775d4b01603470fe21c015393019089904f68724bd37242e4bc6e6d

WORKDIR /app
COPY build/libs/app.jar app.jar

EXPOSE 8080
# Distroless has no shell, so exec form only. The base image sets ENTRYPOINT ["java", "-jar"],
# which would swallow the JVM flags, so override it with the full command.
# --enable-native-access: grpc-netty-shaded uses Unsafe/native on JDK 25; grant it to silence the startup warnings.
# Memory: MaxRAMPercentage is below the 65 default to reserve native/off-heap headroom (suspected
# grpc-netty direct buffers) inside the container limit. MaxDirectMemorySize caps those buffers, which
# also acts as a probe: if they are the OOMKill culprit, exhaustion now throws OutOfMemoryError: Direct
# buffer memory (which ExitOnOutOfMemoryError turns into a JVM exit) instead of a silent exit 137.
# Tuned against common.container.memory in the servicelinker-deployment-config helm chart -- keep the two in sync.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=58.0", \
  "-XX:MaxDirectMemorySize=768m", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "--enable-native-access=ALL-UNNAMED", \
  "-jar", "app.jar"]
