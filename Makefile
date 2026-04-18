ORIGINAL_JAR   := Multivalent20060102.jar
PATCHED_JAR    := Multivalent20060102-patched.jar
DOCKER_IMAGE   := multivalent-patcher

TEST_PDF       := test/many-comments.pdf

.PHONY: all patch test clean help

all: patch

## Generate the patched JAR using Docker
$(PATCHED_JAR): docker-patch/Dockerfile docker-patch/entrypoint.sh docker-patch/PatchEatSpace.java $(ORIGINAL_JAR)
	docker build -t $(DOCKER_IMAGE) docker-patch/
	cat $(ORIGINAL_JAR) | docker run --rm -i $(DOCKER_IMAGE) > $(PATCHED_JAR)

## Build Docker image and apply patch
patch: $(PATCHED_JAR)
	@echo "✓ Patched JAR created: $(PATCHED_JAR)"

## Generate the test PDF (requires python3)
$(TEST_PDF):
	cd test && python3 generate-test-pdf.py

## Run the reproducer against both JARs to confirm the fix
test: $(PATCHED_JAR) $(TEST_PDF)
	@echo ""
	@echo "=== BEFORE patch (expect StackOverflowError) ==="
	-java -Xss256k -cp $(ORIGINAL_JAR) tool.pdf.Info $(TEST_PDF) 2>&1 | \
	  grep -E "StackOverflow|Page count|Filename" || echo "StackOverflowError confirmed"
	@echo ""
	@echo "=== AFTER patch (expect Page count: 1) ==="
	java -Xss256k -cp $(PATCHED_JAR) tool.pdf.Info $(TEST_PDF)
	@echo ""
	@echo "=== Sanity check with a known-good PDF ==="
	java -Xss256k -cp $(PATCHED_JAR) tool.pdf.Info test/test.pdf

clean:
	docker rmi -f $(DOCKER_IMAGE) || true
	rm -f $(PATCHED_JAR) $(TEST_PDF)

help:
	@echo "Multivalent Tools - Make Commands"
	@echo ""
	@echo "  make patch  - Build Docker image and generate patched JAR"
	@echo "  make test   - Test the patch with before/after comparisons"
	@echo "  make clean  - Remove generated artifacts and Docker image"
	@echo "  make help   - Show this help message"
