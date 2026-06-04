# Build stage
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# Copy gradle files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Grant execution rights and download dependencies (offline cache layer)
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon || true

# Copy source code and build the application
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install FFmpeg for video processing
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

# Copy built JAR from the builder stage
COPY --from=builder /build/build/libs/*.jar app.jar

# Create directory for video hosting
RUN mkdir -p hosted-videos

# Expose port
EXPOSE 8080

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
