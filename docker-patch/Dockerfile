FROM eclipse-temurin:11-jdk-alpine

WORKDIR /patch

# Copy ASM dependencies
COPY asm-9.8.jar asm-tree-9.8.jar ./

# Copy and compile the patching Java file
COPY PatchEatSpace.java ./
RUN javac -cp asm-9.8.jar:asm-tree-9.8.jar PatchEatSpace.java

# Create temporary directory and copy entrypoint script
RUN mkdir -p /tmp/jars
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
