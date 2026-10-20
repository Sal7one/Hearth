#!/usr/bin/env python3
"""Validate handoff metadata without downloading weights; optionally render readable cards."""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit, parse_qsl

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
    require(parsed.scheme == "https" and parsed.hostname in {"huggingface.co", "raw.githubusercontent.com", "modelscope.cn"}
            and parsed.netloc == parsed.hostname and not parsed.username and not parsed.password and not parsed.fragment,
            f"{label}: expected a credential-free pinned HTTPS artifact URL")
    require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+",component["repo"])), f"{label}: invalid source repository")
    path = unquote(parsed.path)
    if parsed.hostname == "modelscope.cn":
        entries = parse_qsl(parsed.query,keep_blank_values=True,strict_parsing=True)
        query = dict(entries)
        require(len(entries) == 2 and set(query) == {"Revision", "FilePath"}
                and query["Revision"] == component["revision"]
                and path == f'/api/v1/models/{component["repo"]}/repo' and safe_path(query["FilePath"]),
                f"{label}: URL and source pin disagree")
    else:
        require(not parsed.query, f"{label}: expected a credential-free pinned HTTPS artifact URL")
        middle = "resolve/" if parsed.hostname == "huggingface.co" else ""
        prefix = f'/{component["repo"]}/{middle}{component["revision"]}/'
        require(path.startswith(prefix) and safe_path(path[len(prefix):]), f"{label}: URL and source pin disagree")


def validate_jisr_profiles(model,label):
    profiles=model.get("conversionProfiles")
    require(isinstance(profiles,list) and bool(profiles),f"{label}: missing Jisr conversion profiles")
    live=model.get("liveCatalogIds")
    require(isinstance(live,list) and len(live)==len(set(live)) and bool(live),f"{label}: invalid Jisr live catalog IDs")
    expected={"model.bin","config.json","shared_vocabulary.json","tokenizer.json","LICENSE","NOTICE"}
    seen=set()
    for profile in profiles:
        identity=profile.get("modelId")
        require(identity in live and identity not in seen,f"{label}: conversion modelId must uniquely match liveCatalogIds")
        seen.add(identity)
        require(profile.get("precision") in {"float32","int8"},f"{label}: invalid Jisr precision")
        direction=profile.get("sourceDirection")
        require(direction in {"en-ar","ar-en"},f"{label}: invalid Jisr direction")
        suffix="-int8" if profile["precision"]=="int8" else ""
        require(identity==f"ct2-jisr-50m-{direction}{suffix}",f"{label}: Jisr identity, direction and precision disagree")
        parts=profile.get("components")
        require(isinstance(parts,list),f"{label}: missing converted file list")
        names=[];total=0
        for part in parts:
            name=part.get("filename")
            require(safe_path(name) and "/" not in name,f"{label}: Jisr files must be at the package root")
            names.append(name)
            require(type(part.get("bytes")) is int and part["bytes"]>0,f"{label}: invalid converted size")
            sha=part.get("sha256")
            require(isinstance(sha,str) and bool(HEX64.fullmatch(sha)) and len(set(sha))>1,f"{label}: invalid converted SHA256")
            total+=part["bytes"]
        require(len(names)==len(set(names)) and set(names)==expected,f"{label}: Jisr conversion requires exactly six unique runtime files")
        require(type(profile.get("requiredBytes")) is int and profile["requiredBytes"]==total,f"{label}: converted requiredBytes omits or double-counts files")
        manifest=profile.get("importManifest")
        require(isinstance(manifest,dict) and manifest.get("schemaVersion")==1 and manifest.get("modelId")==identity
                and set(manifest)=={"schemaVersion","modelId"},f"{label}: root import manifest identity mismatch")
        require(profile.get("downloadEnabled") is False,f"{label}: converted packages have no published direct download")
    require(seen==set(live),f"{label}: a live Jisr conversion profile is missing")


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
        if model["family"] == "jisr-mt":
            validate_jisr_profiles(model,label)
        if expected_category == "translation":
            require(bool(model.get("directions")), f"{label}: direction evidence is required")
        if expected_category == "tts":
            require("voices" in model, f"{label}: voice selection evidence is required")
    return models


def compact(value):
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, separators=(", ", ": "))


def component_link(part):
    parsed=urlsplit(part["url"])
    if parsed.hostname=="modelscope.cn":
        return f'https://modelscope.cn/api/v1/models/{part["repo"]}/repo/files?Revision={part["revision"]}&Recursive=true', "Pinned metadata"
    middle="resolve/" if parsed.hostname=="huggingface.co" else ""
    prefix=f'/{part["repo"]}/{middle}{part["revision"]}/'
    file=quote(unquote(parsed.path)[len(prefix):],safe="/")
    host="https://huggingface.co/" if parsed.hostname=="huggingface.co" else "https://github.com/"
    return host+part["repo"]+"/blob/"+part["revision"]+"/"+file, "Pinned file"


def render(inventories):
    lines = ["# Hearth model implementation cards", "",
             "Generated from the three pinned JSON inventories by `python3 scripts/validate-model-handoff.py --render`.", "",
             "These are implementation records. Each card states its current runtime readiness and remaining checks; integrated adapters and planned targets are both included. No S25 results are claimed. Component totals describe required source downloads; they can include conversion-only archives, rather than the final installed footprint.", ""]
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
                source,source_label=component_link(part)
                lines.append(f'| `{part["filename"]}` | {part["role"]} | {part["bytes"]:,} | {part["required"]} | [{source_label}]({source}) |')
            if model["family"]=="jisr-mt" and model.get("conversionProfiles"):
                lines += ["", "Converted runtime packages (generated locally; no direct download):", "",
                          "| Model ID | Precision | Direction | Required bytes | Root files |",
                          "| --- | --- | --- | ---: | --- |"]
                for profile in model["conversionProfiles"]:
                    names=", ".join(f'`{part["filename"]}`' for part in profile["components"])
                    lines.append(f'| `{profile["modelId"]}` | {profile["precision"]} | {profile["sourceDirection"]} | {profile["requiredBytes"]:,} | {names} |')
                lines += ["", "Each ZIP also places `hearth-translation.json` at its root, with schemaVersion 1 and that row’s modelId. The six runtime files above have exact hashes in JSON; source checkpoint totals are separate from converted package totals."]
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
