#!/usr/bin/env python3
"""Host twin of ChatterboxGraph.kt: remove the 90 empty trailing GQA slots.

The Android app ships the same bounded converter in Kotlin. This host script exists so a
desktop proof can prepare the pinned publisher graph without a JVM; it is not part of the
engine. It never interprets weights and refuses any graph that is not the exact pinned
source, producing the exact pinned derived bytes or failing.
"""
import argparse
import hashlib
import sys
from pathlib import Path

SOURCE_BYTES = 228633
SOURCE_SHA256 = "91846c24706116f87b39aac5b13aa1a67243b6f973bc433327d143b8eff9fde6"
OUTPUT_BYTES = 228453
OUTPUT_SHA256 = "60f1451649fcef2014208084f546328f3a4e32c635d57983da7dcf75e9e3dbe8"


class Field:
    __slots__ = ("tag", "wire", "start", "end", "payload")

    def __init__(self, tag, wire, start, end, payload):
        self.tag, self.wire, self.start, self.end, self.payload = tag, wire, start, end, payload


def varint(data: bytes, cursor: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 29, 7):
        if cursor >= len(data):
            raise SystemExit("Truncated Chatterbox protobuf")
        byte = data[cursor]
        cursor += 1
        value |= (byte & 127) << shift
        if byte & 128 == 0:
            return value, cursor
    raise SystemExit("Chatterbox protobuf varint exceeds bound")


def fields(data: bytes) -> list[Field]:
    if len(data) > 1_000_000:
        raise SystemExit("Chatterbox protobuf exceeds bound")
    cursor = 0
    out = []
    while cursor < len(data):
        if len(out) >= 100_000:
            raise SystemExit("Chatterbox protobuf field count exceeds bound")
        start = cursor
        key, cursor = varint(data, cursor)
        tag, wire = key >> 3, key & 7
        if tag <= 0:
            raise SystemExit("Invalid Chatterbox protobuf tag")
        payload = None
        if wire == 0:
            done = False
            for _ in range(10):
                if cursor >= len(data):
                    raise SystemExit("Truncated Chatterbox protobuf scalar")
                byte = data[cursor]
                cursor += 1
                if byte & 128 == 0:
                    done = True
                    break
            if not done:
                raise SystemExit("Invalid Chatterbox protobuf scalar")
        elif wire == 1:
            if len(data) - cursor < 8:
                raise SystemExit("Truncated protobuf fixed64")
            cursor += 8
        elif wire == 2:
            length, cursor = varint(data, cursor)
            if length > len(data) - cursor:
                raise SystemExit("Truncated protobuf bytes")
            payload = data[cursor:cursor + length]
            cursor += length
        elif wire == 5:
            if len(data) - cursor < 4:
                raise SystemExit("Truncated protobuf fixed32")
            cursor += 4
        else:
            raise SystemExit("Unsupported Chatterbox protobuf wire type")
        out.append(Field(tag, wire, start, cursor, payload))
    return out


def encode_varint(value: int) -> bytes:
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def rebuild(data: bytes, entries: list[Field], transform) -> bytes:
    out = bytearray()
    for field in entries:
        changed = transform(field)
        if changed is None:
            out += data[field.start:field.end]
        elif changed:
            out += encode_varint((field.tag << 3) | 2)
            out += encode_varint(len(changed))
            out += changed
    return bytes(out)


def trim(raw: bytes) -> bytes:
    counter = {"graphs": 0, "nodes": 0}

    def model_field(field: Field):
        if field.tag != 7 or field.wire != 2:
            return None
        counter["graphs"] += 1
        if counter["graphs"] != 1:
            raise SystemExit("Duplicate Chatterbox model graph")
        graph = field.payload

        def graph_field(entry: Field):
            if entry.tag != 1 or entry.wire != 2:
                return None
            node = entry.payload
            entries = fields(node)
            operators = [item.payload for item in entries if item.tag == 4 and item.wire == 2]
            domains = [item.payload for item in entries if item.tag == 7 and item.wire == 2]
            if len(operators) != 1 or len(domains) != 1:
                return None
            if operators[0] != b"GroupQueryAttention" or domains[0] != b"com.microsoft":
                return None
            inputs = [item for item in entries if item.tag == 1 and item.wire == 2]
            if len(inputs) != 12 or any(item.payload for item in inputs[9:]):
                raise SystemExit("Chatterbox attention contains active unsupported inputs")
            counter["nodes"] += 1
            index = 0

            def node_field(item: Field):
                nonlocal index
                if item.tag == 1 and item.wire == 2:
                    index += 1
                    if index > 9:
                        return b""
                return None

            return rebuild(node, entries, node_field)

        return rebuild(graph, fields(graph), graph_field)

    output = rebuild(raw, fields(raw), model_field)
    if counter["graphs"] != 1 or counter["nodes"] != 30:
        raise SystemExit(f"Chatterbox graph attention inventory mismatch: {counter}")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    raw = args.source.read_bytes()
    if len(raw) != SOURCE_BYTES or hashlib.sha256(raw).hexdigest() != SOURCE_SHA256:
        raise SystemExit("The publisher graph does not match the pinned size/SHA-256")
    converted = trim(raw)
    if len(converted) != OUTPUT_BYTES or hashlib.sha256(converted).hexdigest() != OUTPUT_SHA256:
        raise SystemExit("The derived graph does not match the pinned size/SHA-256")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(converted)
    print(f"prepared {args.output} {len(converted)} bytes sha256={OUTPUT_SHA256}")


if __name__ == "__main__":
    sys.exit(main())
