#!/bin/sh
set -e

# Display help message
show_help() {
    cat << 'EOF'
Multivalent JAR Patcher

USAGE:
    cat original.jar | docker run --rm -i multivalent-patcher [PATCH_NAME] > patched.jar

DESCRIPTION:
    This Docker container applies bytecode patches to Multivalent20060102.jar
    to fix various bugs found in the legacy tool. Multiple patches are available.

PATCHES:
    eatspace (default)
        Fixes StackOverflowError caused by unbounded mutual recursion in 
        PDFReader.eatSpace() when processing PDFs with many comment lines.
    
    margin
        Fixes NullPointerException in Units.getLength() when margin parameter
        lacks explicit unit suffix. Enables proper margin support in Impose tool.

FLAGS:
    --help     Display this help message and exit

EXAMPLES:
    Build the Docker image:
        docker build -t multivalent-patcher docker-patch/
    
    Apply the default eatspace patch:
        cat Multivalent20060102.jar | docker run --rm -i multivalent-patcher > patched.jar
    
    Apply the margin patch:
        cat Multivalent20060102.jar | docker run --rm -i multivalent-patcher margin > patched.jar
    
    Apply both patches (eatspace first, then margin):
        cat Multivalent20060102.jar | \
          docker run --rm -i multivalent-patcher eatspace | \
          docker run --rm -i multivalent-patcher margin > fully-patched.jar

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

# Determine which patch to apply (default: eatspace)
PATCH_NAME="${1:-eatspace}"

# Validate patch name
case "$PATCH_NAME" in
    eatspace|margin)
        # Valid patch name
        ;;
    *)
        echo "Error: Unknown patch '$PATCH_NAME'" >&2
        echo "Valid patches: eatspace, margin" >&2
        exit 1
        ;;
esac

# Convert patch name to class name
case "$PATCH_NAME" in
    eatspace)
        PATCH_CLASS="EatSpace"
        ;;
    margin)
        PATCH_CLASS="Impose"
        ;;
esac

# Write the input JAR from STDIN to a temporary file
INPUT_JAR="/tmp/jars/input.jar"
OUTPUT_JAR="/tmp/jars/output.jar"

cat > "$INPUT_JAR"

# Run the selected patch
java -cp /patch/asm-9.8.jar:/patch/asm-tree-9.8.jar:/patch "Patch$PATCH_CLASS" "$INPUT_JAR" "$OUTPUT_JAR"

# Output the patched JAR to STDOUT
cat "$OUTPUT_JAR"

# Clean up
rm -f "$INPUT_JAR" "$OUTPUT_JAR"
