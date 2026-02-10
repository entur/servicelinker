FROM bellsoft/liberica-runtime-container:jre-25-slim-musl
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
