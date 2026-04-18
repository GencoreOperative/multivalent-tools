#!/bin/sh
set -e

# Display help message
show_help() {
    cat << 'EOF'
Multivalent JAR Patcher

USAGE:
    cat original.jar | docker run --rm -i multivalent-patcher > patched.jar

DESCRIPTION:
    This Docker container patches Multivalent20060102.jar to eliminate a
    StackOverflowError caused by unbounded mutual recursion in PDFReader.eatSpace().
    
    The patching process:
    1. Reads the original JAR from standard input (STDIN)
    2. Applies the PDFReader.eatSpace() patch using ASM
    3. Outputs the patched JAR to standard output (STDOUT)

FLAGS:
    --help     Display this help message and exit

EXAMPLE:
    docker build -t multivalent-patcher docker-patch/
    cat Multivalent20060102.jar | docker run --rm -i multivalent-patcher > Multivalent20060102-patched.jar

ENVIRONMENT:
    The container requires no environment variables.
    Input and output are handled via standard I/O streams.
EOF
}

# Handle --help flag
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    show_help
    exit 0
fi

# Write the input JAR from STDIN to a temporary file
INPUT_JAR="/tmp/jars/input.jar"
OUTPUT_JAR="/tmp/jars/output.jar"

cat > "$INPUT_JAR"

# Run the patch
java -cp /patch/asm-9.8.jar:/patch/asm-tree-9.8.jar:/patch PatchEatSpace "$INPUT_JAR" "$OUTPUT_JAR"

# Output the patched JAR to STDOUT
cat "$OUTPUT_JAR"

# Clean up
rm -f "$INPUT_JAR" "$OUTPUT_JAR"
