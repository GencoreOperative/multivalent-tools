[Multivalent][] is a old, well-proven, and free tool that I've used for PDF
operations many times before over the years.  It makes imposition easy, for
example.

Last time I tried to find the jar itself and the associated documentation, I
found that they were removed or obfuscated from current versions of Multivalent
and its website.¹  After some digging, I found vintage copies as I remembered
them existing.  Those are archived here.  As best I can tell, the vintage
copies were originally released under the GPL.

You can also browse the Internet Archives' Wayback Machine to see the
[Multivalent website and tool doc at the time][wayback].


## Bug fix — StackOverflowError on PDFs with many comment lines

`Multivalent20060102.jar` crashes with a `StackOverflowError` when processing
PDFs that contain many consecutive `%` comment lines (common in PDFs produced
by Adobe, Quartz, LaTeX, and similar tools):

```
Exception in thread "main" java.lang.StackOverflowError
    at multivalent.std.adaptor.pdf.PDFReader.eatSpace(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.readObject(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.readObject(Unknown Source)
    at multivalent.std.adaptor.pdf.PDFReader.eatSpace(Unknown Source)
    ...
```

### Root cause

`PDFReader` has two overloads of `eatSpace()`.  Both handle comment lines by
calling `readObject()` to consume the comment.  However, `readObject()` itself
calls `eatSpace()` at its entry point to skip leading whitespace — creating
unbounded **mutual recursion**:

```
eatSpace → readObject() → readObject(ra, II) → eatSpace → readObject() → …
```

For *N* consecutive `%` comment lines the stack depth grows by 3*N*, which
overflows in constrained JVM environments (e.g., Docker containers with a small
default thread stack size).

### Fix

A bytecode patcher (`src/PatchEatSpace.java`) rewrites both `eatSpace`
overloads using the [ASM][] library.  The rewritten methods handle comment
lines with an inline byte-reading loop that reads until `\n`, `\r`, or EOF,
eliminating all calls to `readObject()` from within `eatSpace` and breaking
the mutual recursion entirely.

The patched JAR is a drop-in replacement — no change in observable behaviour.

### Applying the patch

```sh
make patch          # produces Multivalent20060102-patched.jar
make test           # before/after comparison
```

Requires Java ≥ 11 and `curl` (to download ASM 9.8 if not already in `lib/`).


¹ See [this post from the author](https://sourceforge.net/p/multivalent/discussion/252478/thread/e7850c31/)


[Multivalent]: http://multivalent.sourceforge.net
[wayback]: http://web.archive.org/web/20060115063350/http://multivalent.sourceforge.net/Tools/index.html
[ASM]: https://asm.ow2.io/

