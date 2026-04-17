#!/bin/bash
# Reproduces StackOverflowError in multivalent.std.adaptor.pdf.PDFReader.eatSpace()
#
# Root cause: eatSpace() handles PDF comments (%) by calling readObject() to consume
# the comment, then recursively calling eatSpace() again. With enough consecutive
# % comment lines, the JVM stack is exhausted.
#
# The -Xss256k flag simulates a constrained JVM stack (similar to Docker defaults
# or certain JVM configurations). Without it, you'd need ~2000+ comments to overflow
# on a default stack; with it, far fewer suffice.

set -e

JAR="./Multivalent20060102.jar"
PDF="./many-comments.pdf"

if [ ! -f "$JAR" ]; then
    echo "ERROR: $JAR not found. Copy it here first."
    exit 1
fi

if [ ! -f "$PDF" ]; then
    echo "Generating test PDF..."
    python3 generate-test-pdf.py
fi

echo "=== Reproducing StackOverflowError in tool.pdf.Info ==="
echo "(using -Xss256k to constrain the JVM stack)"
echo ""
java -Xss256k -cp "$JAR" tool.pdf.Info "$PDF" 2>&1 || true

echo ""
echo "=== Sanity check: test.pdf (works fine) ==="
java -Xss256k -cp "$JAR" tool.pdf.Info ./test.pdf 2>&1 || true
