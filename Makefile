ORIGINAL_JAR   := Multivalent20060102.jar
PATCHED_JAR    := Multivalent20060102-patched.jar

ASM_VERSION      := 9.8
ASM_JAR          := lib/asm-$(ASM_VERSION).jar
ASM_TREE_JAR     := lib/asm-tree-$(ASM_VERSION).jar
ASM_URL          := https://repo1.maven.org/maven2/org/ow2/asm/asm/$(ASM_VERSION)/asm-$(ASM_VERSION).jar
ASM_TREE_URL     := https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/$(ASM_VERSION)/asm-tree-$(ASM_VERSION).jar
ASM_CP           := $(ASM_JAR):$(ASM_TREE_JAR)

BUILD_DIR      := build
PATCHER_CLASS  := $(BUILD_DIR)/PatchEatSpace.class
TEST_PDF       := test/many-comments.pdf

JAVA           := java
JAVAC          := javac

.PHONY: all patch compile test clean

all: patch

## Download ASM jars if not already present
$(ASM_JAR):
	@mkdir -p lib
	@echo "Downloading ASM $(ASM_VERSION)..."
	curl -fsSL $(ASM_URL) -o $(ASM_JAR)

$(ASM_TREE_JAR):
	@mkdir -p lib
	@echo "Downloading ASM Tree $(ASM_VERSION)..."
	curl -fsSL $(ASM_TREE_URL) -o $(ASM_TREE_JAR)

## Compile the patcher
$(PATCHER_CLASS): src/PatchEatSpace.java $(ASM_JAR) $(ASM_TREE_JAR)
	@mkdir -p $(BUILD_DIR)
	$(JAVAC) -cp "$(ASM_CP)" -d $(BUILD_DIR) src/PatchEatSpace.java

compile: $(PATCHER_CLASS)

## Generate the patched JAR
$(PATCHED_JAR): $(PATCHER_CLASS) $(ORIGINAL_JAR)
	$(JAVA) -cp "$(ASM_CP):$(BUILD_DIR)" PatchEatSpace $(ORIGINAL_JAR) $(PATCHED_JAR)

patch: $(PATCHED_JAR)

## Generate the test PDF (requires python3)
$(TEST_PDF):
	cd test && python3 generate-test-pdf.py

## Run the reproducer against both JARs to confirm the fix
test: $(PATCHED_JAR) $(TEST_PDF)
	@echo ""
	@echo "=== BEFORE patch (expect StackOverflowError) ==="
	-$(JAVA) -Xss256k -cp $(ORIGINAL_JAR) tool.pdf.Info $(TEST_PDF) 2>&1 | \
	  grep -E "StackOverflow|Page count|Filename"
	@echo ""
	@echo "=== AFTER patch (expect Page count: 1) ==="
	$(JAVA) -Xss256k -cp $(PATCHED_JAR) tool.pdf.Info $(TEST_PDF)
	@echo ""
	@echo "=== Sanity check with a known-good PDF ==="
	$(JAVA) -Xss256k -cp $(PATCHED_JAR) tool.pdf.Info test/test.pdf

clean:
	rm -rf $(BUILD_DIR) $(PATCHED_JAR) $(TEST_PDF) lib
