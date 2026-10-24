# C:\Users\NARAYANAN\Desktop\SEProject\platform\Dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# create non‑root user (optional but good practice)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# copy the already‑built jar from the host (the jar name may vary)
COPY target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","app.jar"]