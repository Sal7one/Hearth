#!/usr/bin/env python3
"""Verdict tests for the live public catalog auditor (no network)."""
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("catalog_audit", Path(__file__).with_name("audit-model-catalog.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class CatalogAuditTest(unittest.TestCase):
    def setUp(self):
        self.artifact = {"id": "test", "category": "translation", "url":
            "https://huggingface.co/owner/model/resolve/" + "a" * 40 + "/model.gguf",
            "bytes": 100, "sha256": "b" * 64, "license": "MIT"}
        self.remote = {"sha": "a" * 40, "siblings": [{"rfilename": "model.gguf", "size": 100,
            "lfs": {"sha256": "b" * 64}}]}
        self.probe = {"status": "reachable", "http_status": 200, "remote_bytes": 100}

    def check(self, data=None, probe=None, status=200):
        with patch.object(audit, "metadata", return_value=(status, data or self.remote)), \
             patch.object(audit, "probe", return_value=probe or self.probe):
            return audit.check_artifact(self.artifact)

    def test_immutable_publisher_sha_and_size_both_match(self):
        self.assertEqual("verified", self.check()["status"])

    def test_metadata_file_missing_and_real_404_are_missing(self):
        self.assertEqual("missing", self.check({"sha": "a" * 40, "siblings": []})["status"])
        self.assertEqual("missing", self.check(status=404)["status"])

    def test_size_or_hash_mismatch_fails_even_with_working_download(self):
        self.remote["siblings"][0]["size"] = 101
        self.assertEqual("size_mismatch", self.check()["status"])
        self.remote["siblings"][0]["size"] = 100
        self.remote["siblings"][0]["lfs"]["sha256"] = "c" * 64
        self.assertEqual("sha256_mismatch", self.check()["status"])

    def test_reachable_metadata_does_not_hide_blocked_resolve_url(self):
        self.assertEqual("access_blocked", self.check(probe={"status": "access_blocked", "http_status": 403})["status"])

    def test_rate_limits_and_server_failures_are_not_missing(self):
        for code in (429, 500, 502, 503):
            self.assertEqual("retry_needed", self.check(status=code)["status"])
        self.assertEqual("access_blocked", self.check(status=401)["status"])

    def test_release_without_digest_is_explicitly_only_size_checked(self):
        self.artifact["url"] = "https://github.com/owner/repo/releases/download/v1/model.tar.bz2"
        data = {"assets": [{"name": "model.tar.bz2", "size": 100, "digest": None}]}
        result = self.check(data)
        self.assertEqual("reachable_size_only", result["status"])
        self.assertEqual("not_published", result["hash_evidence"])

    def test_revised_repository_is_not_silently_accepted(self):
        self.remote["sha"] = "c" * 40
        result = self.check()
        self.assertEqual("retry_needed", result["status"])
        self.assertIn("revision", result["error"])

    def test_missing_remote_size_does_not_claim_complete_verification(self):
        self.remote["siblings"][0].pop("size")
        self.assertEqual("reachable_hash_only", self.check()["status"])

    def test_large_file_without_published_sha_is_not_given_lfs_hash_evidence(self):
        self.artifact["bytes"] = audit.MAX_SMALL_FILE_BYTES + 1
        self.remote["siblings"][0]["size"] = self.artifact["bytes"]
        self.remote["siblings"][0].pop("lfs")
        result = self.check()
        self.assertEqual("reachable_size_only", result["status"])
        self.assertEqual("not_published", result["hash_evidence"])

    def test_redirect_rejects_http_or_credentials_before_following(self):
        handler = audit.PublicHttpsRedirectHandler()
        for url in ("http://example.com/file", "https://secret@example.com/file"):
            with self.assertRaises(audit.AuditError):
                handler.redirect_request(None, None, 302, "Redirect", {}, url)

    def test_public_request_rejects_embedded_credentials_and_http(self):
        for url in ("http://example.com/file", "https://secret@example.com/file"):
            with self.assertRaises(audit.AuditError):
                audit.request(url)


if __name__ == "__main__":
    unittest.main()
