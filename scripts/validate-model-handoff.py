#!/usr/bin/env python3
"""Validate handoff metadata without downloading weights; optionally render readable cards."""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
CARDS = ROOT / "docs/handoff/models"
HEX40 = re.compile(r"[0-9a-f]{40}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_path(value):
    return (isinstance(value, str) and bool(value) and "\\" not in value
            and not value.startswith("/") and "\x00" not in value
            and all(p not in ("", ".", "..") for p in value.split("/")))


def validate_component(component, label):
    for field in ("filename", "repo", "revision", "url", "bytes", "sha256", "role", "required"):
        require(field in component, f"{label}: missing {field}")
    require(safe_path(component["filename"]), f"{label}: unsafe destination path")
    require(type(component["bytes"]) is int and component["bytes"] > 0, f"{label}: invalid bytes")
    require(type(component["required"]) is bool, f"{label}: required must be boolean")
    require(bool(component["role"]), f"{label}: missing component role")
    require(bool(HEX40.fullmatch(component["revision"])), f"{label}: source revision must be pinned")
    digest = component["sha256"]
    require(isinstance(digest, str) and bool(HEX64.fullmatch(digest)) and len(set(digest)) > 1,
            f"{label}: missing or invalid content SHA256")
    parsed = urlsplit(component["url"])
    require(parsed.scheme == "https" and parsed.hostname == "huggingface.co" and not parsed.username
            and not parsed.password and not parsed.query and not parsed.fragment,
            f"{label}: expected a credential-free pinned HTTPS artifact URL")
    prefix = f'/{component["repo"]}/resolve/{component["revision"]}/'
    path = unquote(parsed.path)
    require(path.startswith(prefix) and safe_path(path[len(prefix):]), f"{label}: URL and source pin disagree")


def validate_inventory(data, expected_category):
    require(data.get("schemaVersion") == 1, "Unsupported handoff schema")
    require(data.get("category") == expected_category, "Inventory category mismatch")
    require(bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", data.get("asOf", ""))), "Missing evidence date")
    models = data.get("models")
    require(isinstance(models, list) and len(models) == 5, "Each category must contain exactly five trained model entries")
    ids = set()
    for model in models:
        label = model.get("id", "unknown")
        for key in ("id", "label", "family", "parameters", "languages", "components", "runtime", "license", "sources"):
            require(key in model and model[key] not in (None, "", []), f"{label}: missing {key}")
        require(label not in ids, f"Duplicate model id: {label}")
        ids.add(label)
        require({"ar", "en"} <= set(model["languages"]), f"{label}: bilingual coverage is required")
        runtime = model["runtime"]
        require(all(key in runtime for key in ("status", "targetBackend", "streaming", "blockers")),
                f"{label}: incomplete runtime readiness")
        require(isinstance(runtime["blockers"], list) and bool(runtime["blockers"]), f"{label}: acceptance gaps must be explicit")
        require(model.get("installEnabled") is not True, f"{label}: handoff records are not production installation manifests")
        require(model["license"].get("name") and model["license"].get("url"), f"{label}: missing license attribution")
        destinations = set()
        for part in model["components"]:
            validate_component(part, label)
            require(part["filename"] not in destinations, f"{label}: duplicate component destination")
            destinations.add(part["filename"])
        total = sum(part["bytes"] for part in model["components"] if part["required"])
        for key in ("downloadBytes", "requiredSourceBytes"):
            if key in model:
                require(type(model[key]) is int and model[key] == total, f"{label}: {key} omits or double-counts required components")
        for variant in model.get("variants", []):
            for part in variant["components"]:
                validate_component(part, f'{label}/{variant["id"]}')
            if "downloadBytes" in variant:
                require(variant["downloadBytes"] == sum(c["bytes"] for c in variant["components"] if c["required"]),
                        f"{label}: alternate quant has incorrect package total")
        if expected_category == "translation":
            require(bool(model.get("directions")), f"{label}: direction evidence is required")
        if expected_category == "tts":
            require("voices" in model, f"{label}: voice selection evidence is required")
    return models


def compact(value):
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, separators=(", ", ": "))


def render(inventories):
    lines = ["# Hearth model implementation cards", "",
             "Generated from the three pinned JSON inventories by `python3 scripts/validate-model-handoff.py --render`.", "",
             "These are implementation records. Two GGUF translators have branch integrations pending real inference checks; the other new adapters remain planned. No S25 results are claimed. Component totals describe required source downloads; they can include conversion-only archives, rather than the final installed footprint.", ""]
    for category, models in inventories.items():
        lines += [f"## {category.upper()}", ""]
        for model in models:
            total = sum(c["bytes"] for c in model["components"] if c["required"])
            lines += [f'### {model["label"]}', "", f'ID: `{model["id"]}`. Family: {model["family"]}.', "",
                      f'Parameters: {compact(model["parameters"])}.', "",
                      f'Languages: {", ".join(model["languages"])}.', "",
                      f'Required source components: **{total / 1_000_000:.3f} MB** ({total:,} bytes).', "",
                      f'Readiness: **{model["runtime"]["status"]}**. Target: {model["runtime"]["targetBackend"]}.', "",
                      f'Streaming: {compact(model["runtime"]["streaming"])}.', ""]
            if "selectedWeightsBytes" in model:
                lines += [f'Selected model and codec weights: **{model["selectedWeightsBytes"] / 1_000_000:.3f} MB**. Required frontend/source inputs and installed footprint are separate.', ""]
            if "sourceArchiveDownloadWarning" in model:
                lines += [model["sourceArchiveDownloadWarning"], ""]
            if "directions" in model:
                lines += [f'Directions: {compact(model["directions"])}.', ""]
            if "voices" in model:
                lines += [f'Voice evidence: {compact(model["voices"])}.', ""]
            lines += [f'License: [{model["license"]["name"]}]({model["license"]["url"]}).', "",
                      "Required work:", ""]
            lines += [f"- {blocker}" for blocker in model["runtime"]["blockers"]]
            lines += ["", "| Component | Role | Bytes | Required | Source |", "| --- | --- | ---: | --- | --- |"]
            for part in model["components"]:
                source = "https://huggingface.co/" + part["repo"] + "/blob/" + part["revision"] + "/" + quote(unquote(urlsplit(part["url"]).path.split("/resolve/", 1)[1].split("/", 1)[1]), safe="/")
                lines.append(f'| `{part["filename"]}` | {part["role"]} | {part["bytes"]:,} | {part["required"]} | [Pinned file]({source}) |')
            lines += ["", "Checksums and complete variant information are in the corresponding JSON inventory.", ""]
            if model.get("quality"):
                lines += ["Evaluation evidence:", "", "```json", json.dumps(model["quality"], ensure_ascii=False, indent=2), "```", ""]
            else:
                lines += ["Exact selected-package Arabic quality is not established in this record. Verify it before making quality claims.", ""]
            lines += ["Sources:", ""] + [f"- [Source {i + 1}]({url})" for i, url in enumerate(model["sources"])] + [""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--render", action="store_true", help="Regenerate docs/handoff/models/README.md")
    args = parser.parse_args()
    inventories = {}
    for category in ("stt", "translation", "tts"):
        data = json.loads((CARDS / f"{category}.json").read_text())
        inventories[category] = validate_inventory(data, category)
    if args.render:
        (CARDS / "README.md").write_text(render(inventories).rstrip() + "\n")
    components = sum(len(model["components"]) for models in inventories.values() for model in models)
    print(f"Validated 15 model records and {components} default-profile components; metadata consistency only, no payloads downloaded.")


if __name__ == "__main__":
    main()
