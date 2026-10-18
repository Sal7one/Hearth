import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock

from apk_delivery import ApkIdentity, configured_identity, parse_identity, signature_verdict, verify_delivery


CERTIFICATE = "7a9256582e2627e5b715ef2648373e52d62c512127f40b2aff3ccbcc787d5792"
SIGNED = "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: " + CERTIFICATE + "\n"
UNSIGNED = "DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n"
BUILD = 'applicationId = "com.sal7one.transiber"\nversionCode = 85\nversionName = "0.25.1"\n'
BADGING = "package: name='com.sal7one.transiber.qa' versionCode='85' versionName='0.25.1' platformBuildVersionCode='36'\napplication-label:'Hearth'\n"


class ApkDeliveryTest(unittest.TestCase):
    def test_build_types_use_distinct_package_identities(self):
        self.assertEqual(configured_identity(BUILD, "qa"), ApkIdentity("com.sal7one.transiber.qa", 85, "0.25.1"))
        self.assertEqual(configured_identity(BUILD, "release"), ApkIdentity("com.sal7one.transiber", 85, "0.25.1"))

    def test_ambiguous_or_unreadable_build_version_is_rejected(self):
        for script in (BUILD + 'versionCode = 84\n', BUILD.replace('versionCode = 85', 'versionCode = versionFromFile')):
            with self.subTest(script=script), self.assertRaises(ValueError):
                configured_identity(script, "qa")

    def test_manifest_requires_complete_unambiguous_identity(self):
        for badging in ("", BADGING + BADGING, BADGING.replace("versionCode='85'", "versionCode='unknown'"),
                        BADGING.replace("versionName='0.25.1'", ""), BADGING.replace("versionCode='85'", "versionCode='0'")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                parse_identity(badging)

    def test_unsigned_release_cannot_pass_delivery(self):
        with self.assertRaisesRegex(ValueError, "cannot be delivered"):
            signature_verdict(1, UNSIGNED)

    def test_only_explicit_pre_sign_mode_accepts_unsigned_input(self):
        self.assertIsNone(signature_verdict(1, UNSIGNED, allow_unsigned_for_signing=True))

    def test_pre_sign_mode_still_rejects_corrupt_signatures_and_tool_failure(self):
        failures = ("DOES NOT VERIFY\nERROR: APK Signature Scheme v2 signer #1: APK integrity check failed\n",
                    UNSIGNED + "ERROR: signature block is malformed\n", "Unable to access jarfile apksigner.jar")
        for output in failures:
            with self.subTest(output=output), self.assertRaises(ValueError):
                signature_verdict(1, output, allow_unsigned_for_signing=True)

    def test_verification_success_requires_certificate_evidence(self):
        self.assertEqual(signature_verdict(0, SIGNED), frozenset({CERTIFICATE}))
        with self.assertRaisesRegex(ValueError, "certificate"):
            signature_verdict(0, "Verifies\n")

    def test_stale_version_or_wrong_build_type_cannot_pass_even_when_signed(self):
        expected = configured_identity(BUILD, "qa")
        for badging in (BADGING.replace(".qa'", "'"), BADGING.replace("versionCode='85'", "versionCode='84'"),
                        BADGING.replace("versionName='0.25.1'", "versionName='0.25.0'")):
            runner = Mock(return_value=subprocess.CompletedProcess([], 0, badging, ""))
            with self.subTest(badging=badging), self.assertRaisesRegex(ValueError, "rebuild"):
                verify_delivery(Path("test.apk"), Path("aapt2"), Path("apksigner"), expected, run=runner)
            self.assertEqual(runner.call_count, 1)

    def test_delivery_runs_platform_verifier_on_actual_apk_and_returns_signer(self):
        runner = Mock(side_effect=[subprocess.CompletedProcess([], 0, BADGING, ""),
                                   subprocess.CompletedProcess([], 0, SIGNED, "a Java warning")])
        signers = verify_delivery(Path("test.apk"), Path("aapt2"), Path("apksigner"),
                                  configured_identity(BUILD, "qa"), run=runner)
        self.assertEqual(signers, frozenset({CERTIFICATE}))
        self.assertEqual(runner.call_args_list[1].args[0], ["apksigner", "verify", "--verbose", "--print-certs", "test.apk"])

    def test_unreadable_manifest_stops_before_signature_check(self):
        runner = Mock(return_value=subprocess.CompletedProcess([], 1, "", "invalid AndroidManifest.xml"))
        with self.assertRaisesRegex(ValueError, "Cannot read APK identity"):
            verify_delivery(Path("test.apk"), Path("aapt2"), Path("apksigner"), configured_identity(BUILD, "qa"), run=runner)
        self.assertEqual(runner.call_count, 1)


if __name__ == "__main__":
    unittest.main()
