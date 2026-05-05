"""Build packages/core/tests/fixtures/missing_sample_tiny.als from tiny.als.

Run once locally: `uv run python scripts/build_relink_fixture.py`. The generated
.als is gitignored alongside the other test fixtures — every contributor builds
them locally from the base tiny.als.
"""

import gzip
from pathlib import Path

from lxml import etree

base = Path("packages/core/tests/fixtures/tiny.als")
out = Path("packages/core/tests/fixtures/missing_sample_tiny.als")

with gzip.open(base, "rb") as fh:
    tree = etree.parse(fh, etree.XMLParser(huge_tree=True))
root = tree.getroot()

sample_refs = list(root.iter("SampleRef"))
assert sample_refs, "tiny.als has no SampleRef"
template = sample_refs[0]
parent = template.getparent()
clone = etree.fromstring(etree.tostring(template))
file_ref = clone.find("FileRef")
for tag in ("HasRelativePath", "RelativePathType", "RelativePath", "Name", "Type", "Data"):
    el = file_ref.find(tag)
    if el is not None:
        file_ref.remove(el)
path_node = etree.SubElement(file_ref, "Path")
path_node.set("Value", "Samples/relink_test_kick.wav")
has_rel = etree.SubElement(file_ref, "HasRelativePath")
has_rel.set("Value", "true")
rel_path_node = etree.SubElement(file_ref, "RelativePath")
rel_path_node.set("Value", "Samples/relink_test_kick.wav")
name_node = etree.SubElement(file_ref, "Name")
name_node.set("Value", "relink_test_kick.wav")
parent.append(clone)

out.parent.mkdir(parents=True, exist_ok=True)
with gzip.open(out, "wb") as fh:
    tree.write(fh, xml_declaration=True, encoding="UTF-8")
print(f"wrote {out}")
