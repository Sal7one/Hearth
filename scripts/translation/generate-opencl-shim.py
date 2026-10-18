#!/usr/bin/env python3
"""Generate typed OpenCL forwards for the pinned backend's actual API calls.

Only the Khronos header supplies signatures. No vendor library is linked or
bundled; the private DSO resolves these calls after an explicit user choice.
"""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('--headers', type=Path, required=True)
parser.add_argument('--source', type=Path, action='append', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
source = '\n'.join(path.read_text() for path in args.source)
required = set(re.findall(r'\b(cl[A-Z][A-Za-z0-9_]+)\s*\(', source))
header = re.sub(r'/\*.*?\*/', '', (args.headers/'CL/cl.h').read_text(), flags=re.S)
prototypes = {}
for match in re.finditer(r'extern\s+CL_API_ENTRY\s+(?:CL_API_PREFIX\w+\s+)?(\w+)\s+CL_API_CALL\s+(cl\w+)\s*\((.*?)\)\s*CL_API_SUFFIX[^;]*;', header, re.S):
    result, name, signature = match.groups()
    prototypes[name] = (result, re.sub(r'\s+', ' ', signature).strip())
missing = required - prototypes.keys()
if missing:
    raise SystemExit('OpenCL API signatures missing: ' + ', '.join(sorted(missing)))
lines = ['// Generated from pinned Khronos cl.h and the backend API call sites.',
         '#include "opencl_loader.h"']
for name in sorted(required):
    result, signature = prototypes[name]
    parameters = []; start = depth = 0
    for i, value in enumerate(signature):
        if value == '(': depth += 1
        elif value == ')': depth -= 1
        elif value == ',' and depth == 0:
            parameters.append(signature[start:i].strip()); start = i + 1
    parameters.append(signature[start:].strip())
    names = []
    for parameter in parameters:
        if parameter == 'void': continue
        callback = re.search(r'\*\s*(\w+)\s*\)', parameter)
        ordinary = re.search(r'(\w+)\s*(?:\[[^\]]*\])?$', parameter)
        if not (callback or ordinary): raise SystemExit('Cannot parse argument: ' + parameter)
        names.append((callback or ordinary).group(1))
    lines.extend([f'extern "C" CL_API_ENTRY {result} CL_API_CALL {name}({signature}) {{',
        f'    static const auto function = hearth::opencl::resolve<decltype(&::{name})>("{name}");',
        f'    return function({", ".join(names)});', '}'])
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text('\n'.join(lines)+'\n')
print(f'Generated {len(required)} typed OpenCL driver forwards: {args.output}')
