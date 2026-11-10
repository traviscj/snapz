# Build first on the host:  (cd server && . bin/activate-hermit && ./gradlew test installDist)
# then from the repo root:  docker build -f deploy/Dockerfile -t snapz:local .
FROM amazoncorretto:21-alpine
WORKDIR /app
COPY server/build/install/snapz/ /app/
ENV SNAPZ_CONFIG=/config/snapz.yaml
EXPOSE 8788
ENTRYPOINT ["/app/bin/snapz"]
