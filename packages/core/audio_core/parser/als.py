from __future__ import annotations

import gzip
from pathlib import Path

from lxml import etree


def als_xml(path: str | Path) -> etree._Element:
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(p)
    with gzip.open(p, "rb") as fh:
        return etree.parse(fh).getroot()
