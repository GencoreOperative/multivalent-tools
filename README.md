# Multivalent Tools

## About Multivalent

[Multivalent][] is a legacy, well-proven, and free Java tool for advanced PDF operations. Originally developed at UC Berkeley, it was widely used for tasks like imposition, page manipulation, and annotation before being archived.

### Project History

- **Original Project**: [Multivalent on SourceForge][multivalent-sf] (archived, no longer in active development)
- **This Repository**: A re-upload and archival of `Multivalent20060102.jar` by [@tsibley](https://github.com/tsibley/multivalent-tools), who discovered and archived vintage copies when the original tool became difficult to locate
- **License**: GPL (as best as can be determined from original releases)

The original Multivalent website and documentation can be viewed through the [Internet Archive Wayback Machine][wayback].

### Why This Matters

The original Multivalent project is no longer under active development, and the JAR files and documentation have been removed or obfuscated from the SourceForge project. This repository preserves `Multivalent20060102.jar` along with critical bug fixes that resolve issues found when working with PDF files containing comments and when using the Impose tool's margin parameter.


## Bug Fixes

This repository includes patches for two bugs found in the original `Multivalent20060102.jar`:

### Bug 1: StackOverflowError with PDFs Containing Comment Lines

#### Symptoms

`Multivalent20060102.jar` crashes with a `StackOverflowError` when processing PDFs that contain many consecutive `%` comment lines. This is common in PDFs produced by Adobe, Quartz, LaTeX, and similar tools:

```
Exception in thread "main" java.lang.StackOverflowError
    at multivalent.std.adaptor.pdf.PDFReader.eatSpace(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.readObject(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.readObject(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.eatSpace(Unknown Source)
    ...
```

#### Root Cause

The `PDFReader` class has two overloads of the `eatSpace()` method. Both methods handle comment lines (bytes starting with `%`) by calling `readObject()` to consume the comment. However, `readObject()` itself calls `eatSpace()` at its entry point to skip leading whitespace. This creates **unbounded mutual recursion**:

```
eatSpace → readObject() → readObject(ra, II) → eatSpace → readObject() → …
```

For *N* consecutive `%` comment lines, the stack depth grows by 3*N*. In constrained JVM environments—such as Docker containers with small default thread stack sizes—this quickly overflows the stack.

#### The Fix

A bytecode patcher (`docker-patch/PatchEatSpace.java`) uses the [ASM][] library to rewrite both `eatSpace` overloads at the bytecode level. The rewritten methods handle comment lines with a direct inline byte-reading loop that reads until `\n`, `\r`, or EOF. This eliminates all calls to `readObject()` from within `eatSpace` and breaks the mutual recursion entirely.


### Bug 2: NullPointerException in Impose Tool with Margin Parameter

#### Symptoms

The Impose tool crashes with `NullPointerException` when using the `-margin` parameter without an explicit unit suffix:

```
java -jar Multivalent20060102.jar tool.pdf.Impose -margin 20 -paper A4 input.pdf
```

Error message:
```
Exception in thread "main" java.lang.NullPointerException: Cannot invoke String.trim() because <parameter1> is null
    at multivalent.util.Units.convertLength(Unknown Source)
    at multivalent.util.Units.getLength(Unknown Source)
    at tool.pdf.Impose.<init>(Unknown Source)
    ...
```

#### Root Cause

The `Units.getLength(String, String)` method parses strings like "10bp" (10 big points) to extract the numeric value and optional unit suffix. It uses this regex pattern:

```
([-+0-9.]+)\s*(\w+)?
```

Group 1 captures the number, Group 2 (optional) captures the unit. When a user provides a margin value without a unit (e.g., "20" instead of "20bp"), `matcher.group(2)` returns `null`. This `null` value was passed directly to `convertLength()`, which immediately called `null.trim()`, causing the NullPointerException.

The bug was likely never caught in the original implementation because:
1. Users typically provide unit suffixes when margin values are numeric
2. The Impose tool documentation didn't clearly specify that units were optional
3. The 2006-era code predates modern null-safety practices

#### The Fix

A bytecode patcher (`docker-patch/PatchImpose.java`) uses the [ASM][] library to rewrite the `Units.getLength()` method to include proper null checking. After extracting the unit with `matcher.group(2)`, the patched code:

1. Duplicates the matcher result on the stack
2. Uses IFNONNULL to check for null
3. If null, pops the null value and loads a default unit ("px")
4. Continues with the valid unit value

This enables users to provide margin values as simple numbers (e.g., `-margin 20`) while the tool defaults to pixels ("px") when no explicit unit is given. The patched behavior is backward compatible with explicit units (e.g., `-margin 20bp` still works correctly).

#### ASM Bytecode Pattern

The null-safe unit handling follows this pattern:

```
aload_2           // Load matcher
bipush 2
invokevirtual group(I)   // Returns null if group 2 absent
dup               // Duplicate for null check
ifnonnull :skip   // If not null, skip default
pop               // Pop null value
ldc "px"          // Load default unit
:skip:
// Continue with valid unit value
```


## Applying the Patches

### Option 1: Using Docker (Recommended)

Build a Docker image that applies patches:

```sh
docker build -t multivalent-patcher docker-patch/
```

Apply a single patch to a JAR:

```sh
# Apply eatspace patch (default)
cat Multivalent20060102.jar | docker run --rm -i multivalent-patcher > patched.jar

# Apply margin patch
cat Multivalent20060102.jar | docker run --rm -i multivalent-patcher margin > patched.jar
```

Apply multiple patches in sequence:

```sh
# Apply both eatspace and margin patches
cat Multivalent20060102.jar | \
  docker run --rm -i multivalent-patcher eatspace | \
  docker run --rm -i multivalent-patcher margin > fully-patched.jar
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
- Supports multiple patches and composition

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
- **Patching Tools**: Both `PatchEatSpace.java` and `PatchImpose.java` compiled and ready
- **I/O**: Reads the original JAR from STDIN, outputs the patched JAR to STDOUT
- **Composition**: Patches can be chained via pipes for multi-patch application
- **Cleanup**: Automatically removes temporary files


[Multivalent]: http://multivalent.sourceforge.net
[multivalent-sf]: https://sourceforge.net/projects/multivalent/
[wayback]: http://web.archive.org/web/20060115063350/http://multivalent.sourceforge.net/Tools/index.html
[ASM]: https://asm.ow2.io/

