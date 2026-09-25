#!/usr/bin/env python3
"""Generate Forge public-f access transformer entries from class/member names."""

import argparse
import struct
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "build" / "moddev" / "artifacts"
DEFAULT_AT = ROOT / "src" / "main" / "resources" / "META-INF" / "accesstransformer.cfg"


class ClassReader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def u1(self):
        value = self.data[self.offset]
        self.offset += 1
        return value

    def u2(self):
        value = struct.unpack_from(">H", self.data, self.offset)[0]
        self.offset += 2
        return value

    def u4(self):
        value = struct.unpack_from(">I", self.data, self.offset)[0]
        self.offset += 4
        return value

    def skip(self, size):
        self.offset += size


def read_members(class_bytes):
    reader = ClassReader(class_bytes)
    if reader.u4() != 0xCAFEBABE:
        raise ValueError("Invalid Java class file")
    reader.skip(4)  # minor and major versions
    pool = [None] * reader.u2()
    index = 1
    while index < len(pool):
        tag = reader.u1()
        if tag == 1:  # UTF8
            length = reader.u2()
            pool[index] = class_bytes[reader.offset:reader.offset + length].decode("utf-8", "replace")
            reader.skip(length)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.skip(4)
        elif tag in (5, 6):
            reader.skip(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            reader.skip(2)
        elif tag == 15:
            reader.skip(3)
        else:
            raise ValueError(f"Unsupported constant pool tag: {tag}")
        index += 1

    reader.skip(6)  # access flags, this class, super class
    reader.skip(2 * reader.u2())  # interfaces

    def member_table():
        members = []
        for _ in range(reader.u2()):
            reader.skip(2)  # access flags
            name = pool[reader.u2()]
            descriptor = pool[reader.u2()]
            for _ in range(reader.u2()):
                reader.skip(2)  # attribute name index
                reader.skip(reader.u4())
            members.append((name, descriptor))
        return members

    return member_table(), member_table()


def resolve_class(archive, class_name):
    requested = class_name.removesuffix(".class").replace(".", "/")
    paths = set(archive.namelist())
    candidate = requested + ".class"
    if candidate in paths:
        return requested

    # Accept Outer.Inner in addition to the JVM's Outer$Inner spelling.
    inner_candidate = requested
    while "/" in inner_candidate:
        head, tail = inner_candidate.rsplit("/", 1)
        inner_candidate = head + "$" + tail
        if inner_candidate + ".class" in paths:
            return inner_candidate

    if "/" not in requested:
        matches = [name[:-6] for name in paths if name.endswith("/" + requested + ".class")]
        if len(matches) == 1:
            return matches[0]
        if matches:
            raise ValueError("Ambiguous class name; use a full name:\n  " + "\n  ".join(sorted(matches)))
    raise ValueError(f"Class not found in JAR: {class_name}")


def read_srg_mappings(mapping_file, internal_class):
    target_fields = {}
    target_methods = {}
    mapped_class = None
    in_class = False
    with mapping_file.open(encoding="utf-8") as stream:
        for line in stream:
            if not line.startswith("\t"):
                parts = line.split()
                in_class = bool(parts) and parts[0] == internal_class
                if in_class:
                    mapped_class = parts[1]
                elif mapped_class is not None:
                    break
                continue
            if not in_class:
                continue
            parts = line.split()
            if len(parts) == 2:
                target_fields[parts[0]] = parts[1]
            elif len(parts) == 3:
                target_methods[(parts[0], parts[1])] = parts[2]
    return mapped_class, target_fields, target_methods


def default_jar():
    jars = list(ARTIFACTS.glob("forge-*-merged.jar"))
    if not jars:
        raise ValueError("No Forge merged JAR found. Run gradlew build first, or pass --jar.")
    return max(jars, key=lambda path: path.stat().st_mtime)


def generate(jar_path, mapping_path, class_name, member_name, kind):
    with zipfile.ZipFile(jar_path) as archive:
        internal_class = resolve_class(archive, class_name)
        fields, methods = read_members(archive.read(internal_class + ".class"))

    is_minecraft = internal_class.startswith("net/minecraft/") or internal_class.startswith("com/mojang/")
    if mapping_path.exists():
        mapped_class, field_map, method_map = read_srg_mappings(mapping_path, internal_class)
    else:
        mapped_class, field_map, method_map = None, {}, {}
    if is_minecraft and mapped_class is None:
        raise ValueError("SRG mapping for this Minecraft class is missing. Run gradlew build first.")

    matched_fields = [(name, desc) for name, desc in fields if name == member_name]
    matched_methods = [(name, desc) for name, desc in methods if name == member_name]
    if kind == "auto":
        if matched_fields and matched_methods:
            raise ValueError("Name matches both a field and a method; specify --kind field or --kind method.")
        kind = "field" if matched_fields else "method"
    matches = matched_fields if kind == "field" else matched_methods
    if not matches:
        raise ValueError(f"{kind.title()} '{member_name}' is not declared in {internal_class.replace('/', '.')}.")

    target_class = (mapped_class or internal_class).replace("/", ".")
    lines = []
    for name, descriptor in matches:
        if kind == "field":
            mapped_name = field_map.get(name)
            if is_minecraft and mapped_name is None:
                raise ValueError(f"No SRG mapping found for field {name}.")
            entry = f"public-f {target_class} {mapped_name or name}"
        else:
            mapped_name = method_map.get((name, descriptor))
            if is_minecraft and mapped_name is None:
                raise ValueError(f"No SRG mapping found for method {name}{descriptor}.")
            entry = f"public-f {target_class} {mapped_name or name}{descriptor}"
        if entry not in lines:
            lines.append(entry)
    return lines


def append_new_entries(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    old_lines = {line.split("#", 1)[0].strip() for line in existing.splitlines()}
    new_entries = [entry for entry in entries if entry not in old_lines]
    if new_entries:
        with path.open("a", encoding="utf-8", newline="\n") as stream:
            if existing and not existing.endswith(("\n", "\r")):
                stream.write("\n")
            stream.write("\n".join(new_entries) + "\n")
    return len(new_entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("class_name", nargs="?", help="full class name, slash path, or unique short class name")
    parser.add_argument("member_name", nargs="?", help="field or method name; method descriptor is found automatically")
    parser.add_argument("--kind", choices=("auto", "field", "method"), default="auto")
    parser.add_argument("--jar", type=Path, help="JAR to inspect (default: project's Forge merged JAR)")
    parser.add_argument("--mappings", type=Path, default=ARTIFACTS / "namedToIntermediate.tsrg")
    parser.add_argument("--write", action="store_true", help="append new entries to accesstransformer.cfg")
    parser.add_argument("--output", type=Path, default=DEFAULT_AT, help="AT file used with --write")
    args = parser.parse_args()

    if not args.class_name or not args.member_name:
        if not sys.stdin.isatty():
            parser.error("class_name and member_name are required in non-interactive mode")
        args.class_name = args.class_name or input("Class: ").strip()
        args.member_name = args.member_name or input("Field or method: ").strip()
    if not args.class_name or not args.member_name:
        parser.error("class and member names cannot be empty")

    try:
        entries = generate(args.jar or default_jar(), args.mappings, args.class_name, args.member_name, args.kind)
        for entry in entries:
            print(entry)
        if len(entries) > 1:
            print(f"# {len(entries)} overloads found; all were included.", file=sys.stderr)
        if args.write:
            count = append_new_entries(args.output, entries)
            print(f"# Added {count} new entr{'y' if count == 1 else 'ies'} to {args.output}", file=sys.stderr)
    except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        parser.exit(1, f"Error: {error}\n")


if __name__ == "__main__":
    main()
