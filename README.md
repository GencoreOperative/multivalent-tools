# Multivalent Tools

## About Multivalent

[Multivalent][] is a legacy, well-proven, and free Java tool for advanced PDF operations. Originally developed at UC Berkeley, it was widely used for tasks like imposition, page manipulation, and annotation before being archived.

### Project History

- **Original Project**: [Multivalent on SourceForge][multivalent-sf] (archived, no longer in active development)
- **This Repository**: A re-upload and archival of `Multivalent20060102.jar` by [@tsibley](https://github.com/tsibley/multivalent-tools), who discovered and archived vintage copies when the original tool became difficult to locate
- **License**: GPL (as best as can be determined from original releases)

The original Multivalent website and documentation can be viewed through the [Internet Archive Wayback Machine][wayback].

### Why This Matters

The original Multivalent project is no longer under active development, and the JAR files and documentation have been removed or obfuscated from the SourceForge project. This repository preserves `Multivalent20060102.jar` along with a critical bug fix that resolves an issue found when working with PDF files that contain comments.


## The Bug: StackOverflowError with PDFs Containing Comment Lines

### Symptoms

`Multivalent20060102.jar` crashes with a `StackOverflowError` when processing PDFs that contain many consecutive `%` comment lines. This is common in PDFs produced by Adobe, Quartz, LaTeX, and similar tools:

```
Exception in thread "main" java.lang.StackOverflowError
    at multivalent.std.adaptor.pdf.PDFReader.eatSpace(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.readObject(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.readObject(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.eatSpace(Unknown Source)
    ...
```

### Root Cause

The `PDFReader` class has two overloads of the `eatSpace()` method. Both methods handle comment lines (bytes starting with `%`) by calling `readObject()` to consume the comment. However, `readObject()` itself calls `eatSpace()` at its entry point to skip leading whitespace. This creates **unbounded mutual recursion**:

```
eatSpace → readObject() → readObject(ra, II) → eatSpace → readObject() → …
```

For *N* consecutive `%` comment lines, the stack depth grows by 3*N*. In constrained JVM environments—such as Docker containers with small default thread stack sizes—this quickly overflows the stack.

### The Fix

A bytecode patcher (`docker-patch/PatchEatSpace.java`) uses the [ASM][] library to rewrite both `eatSpace` overloads at the bytecode level. The rewritten methods handle comment lines with a direct inline byte-reading loop that reads until `\n`, `\r`, or EOF. This eliminates all calls to `readObject()` from within `eatSpace` and breaks the mutual recursion entirely.

The patched JAR is a drop-in replacement with **identical observable behavior** — only the internal implementation changes.

## Applying the Patch

### Option 1: Using Docker (Recommended)

Build a Docker image that applies the patch:

```sh
docker build -t multivalent-patcher docker-patch/
```

Then use it to patch a JAR:

```sh
cat Multivalent20060102.jar | docker run --rm -i multivalent-patcher > Multivalent20060102-patched.jar
```

For help and usage information:

```sh
docker run --rm multivalent-patcher --help
```

**Advantages**:
- No local Java dependencies required (Docker handles everything)
- Isolated build environment
- Reproducible across different systems
- Easy to integrate into CI/CD pipelines

### Option 2: Using Make

If you prefer local patching with Java ≥ 11:

```sh
make patch          # produces Multivalent20060102-patched.jar
make test           # before/after comparison (optional)
```

Requires:
- Java ≥ 11
- `curl` (to download ASM 9.8 if not already cached)

### Docker Image Details

The Docker image in `docker-patch/`:
- **Base**: `eclipse-temurin:11-jdk-alpine` (lightweight Java 11 environment)
- **Dependencies**: ASM 9.8 and ASM Tree 9.8 libraries
- **Patching Tool**: `PatchEatSpace.java` compiled and ready to execute
- **I/O**: Reads the original JAR from STDIN, outputs the patched JAR to STDOUT
- **Cleanup**: Automatically removes temporary files


[Multivalent]: http://multivalent.sourceforge.net
[multivalent-sf]: https://sourceforge.net/projects/multivalent/
[wayback]: http://web.archive.org/web/20060115063350/http://multivalent.sourceforge.net/Tools/index.html
[ASM]: https://asm.ow2.io/

