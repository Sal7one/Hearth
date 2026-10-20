#!/usr/bin/env python3
"""Disable optional Linux pthread affinity API unavailable in Android API 28.
Normal scheduling is unchanged: the session never requests thread pinning.
"""
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
old='#if !defined(__linux__) || defined(_OPENMP)'
assert source.count(old)==1
Path(sys.argv[2]).write_text(source.replace(old,'#if !defined(__linux__) || defined(_OPENMP) || defined(__ANDROID__)'))
