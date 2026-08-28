#!/usr/bin/env python3
"""Generate the books a throwaway Grimmory is seeded with.

Called by hack/grimmory-dev; not meant to be run on its own.

Two things are being arranged here, and both matter to what the tests
downstream can actually prove:

* **More than one page of EPUBs.** Liseur's Grimmory catalog client asks
  for 200 books at a time. Rather than add a test-only page-size hook to
  a network client -- a seam that exists only to be lied to -- the shelf
  is simply made bigger than one page, so a second HTTP request has to
  happen for the catalog to come back whole.
* **At least one book that is not an EPUB.** The client filters on the
  shim's `media.mediaType`, and a filter with nothing to reject is not a
  filter that has been tested. A CBZ is the cheapest non-EPUB Grimmory
  will ingest.

Everything written here is generated: no downloads, no third-party
files, nothing copyrighted. The text is filler.
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import zipfile

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:{uuid}</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:creator>{author}</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
    <meta name="cover" content="cover-image"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>
"""

NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <head><title>{title}</title></head>
  <body>
    <nav epub:type="toc" id="toc">
      <ol><li><a href="chapter1.xhtml">Chapter One</a></li></ol>
    </nav>
  </body>
</html>
"""

CHAPTER = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>{title}</title></head>
  <body>
    <h1>{title}</h1>
    <p>{filler}</p>
  </body>
</html>
"""

FILLER = (
    "This book exists so that a shelf can be counted. It carries no "
    "meaning and is not worth reading. "
)

# A 1x1 white PNG, so the CBZ is a comic archive Grimmory will accept
# rather than an empty zip it may skip.
PIXEL_PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000d4944415478da63fcffff3f0300050001f6a2b1c4"
    "0000000049454e44ae426082"
)


def png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """A solid-colour PNG, built without a third-party imaging library.

    Every generated EPUB carries one. A book with no cover comes back
    from Grimmory's thumbnail endpoint as a 404, and covers are exactly
    where the client's URL building can go wrong unnoticed -- a broken
    download is loud, a broken cover is just a grey box. The end-to-end
    check asserts a cover actually loads, so there has to be one.
    """
    import struct
    import zlib

    raw = b"".join(
        b"\x00" + bytes(rgb) * width for _ in range(height)
    )

    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def write_epub(path: pathlib.Path, index: int) -> None:
    title = f"Filler Volume {index:03d}"
    author = f"Generator, Test {index % 7}"
    uuid = f"00000000-0000-4000-8000-{index:012d}"
    # A different hue per book, so a wrong cover is visible as a wrong
    # colour rather than hiding behind a shelf of identical squares.
    shade = (60 + (index * 7) % 180, 90 + (index * 13) % 150, 140 + (index * 3) % 110)
    # ZIP_STORED for mimetype, first and uncompressed, as the spec asks.
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(
            zipfile.ZipInfo("mimetype"),
            "application/epub+zip",
            compress_type=zipfile.ZIP_STORED,
        )
        z.writestr("META-INF/container.xml", CONTAINER)
        z.writestr("OEBPS/content.opf", OPF.format(uuid=uuid, title=title, author=author))
        z.writestr("OEBPS/nav.xhtml", NAV.format(title=title))
        z.writestr("OEBPS/cover.png", png(120, 180, shade))
        z.writestr(
            "OEBPS/chapter1.xhtml",
            CHAPTER.format(title=title, filler=FILLER * 40),
        )


def write_cbz(path: pathlib.Path) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for page in range(1, 4):
            z.writestr(f"{page:03d}.png", PIXEL_PNG)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument(
        "-n",
        "--count",
        type=int,
        default=210,
        help="how many EPUBs to generate; must exceed the client's 200 "
        "page size for the catalog to span two pages (default: 210)",
    )
    args = parser.parse_args()

    if args.count <= 200:
        print(
            f"refusing to generate {args.count} EPUBs: the catalog client "
            "asks for 200 at a time, so this would fit in one page and "
            "the pagination check downstream would prove nothing",
            file=sys.stderr,
        )
        return 2

    books = args.directory
    books.mkdir(parents=True, exist_ok=True)

    for index in range(1, args.count + 1):
        write_epub(books / f"filler-{index:03d}.epub", index)
    write_cbz(books / "not-a-book.cbz")

    print(f"{args.count} EPUBs and 1 CBZ written to {books}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
