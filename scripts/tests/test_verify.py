import importlib.util
from pathlib import Path
import tempfile
import unittest
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).parents[1]))
spec = importlib.util.spec_from_file_location("verify", Path(__file__).parents[1] / "verify.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class VerificationGateTest(unittest.TestCase):
    def gate(self, messages=(), skips=(), suite_message="", malformed=False):
        with tempfile.TemporaryDirectory() as directory:
            suite = ET.Element("testsuite")
            ET.SubElement(suite, "system-out").text = "validation=true syncValidation=true\n" + suite_message
            control = ET.SubElement(suite, "testcase", classname=verify.CONTROL[0], name=verify.CONTROL[1] + "()")
            ET.SubElement(control, "system-err").text = "[vk-validation] [SYNC-HAZARD-WRITE-AFTER-WRITE] vkCmdFillBuffer(): deliberate hazard"
            for test, message in messages:
                case = control if test == verify.CONTROL[1] else ET.SubElement(suite, "testcase", classname="me.cortex.voxy.vk.OtherTest", name=test)
                ET.SubElement(case, "system-out").text = message
            for classname, name in skips:
                case = ET.SubElement(suite, "testcase", classname=classname, name=name + "()")
                ET.SubElement(case, "skipped")
            file = Path(directory) / "TEST-fixture.xml"
            file.write_text("invalid" if malformed else ET.tostring(suite, encoding="unicode"))
            return verify.junit_result(Path(directory))

    def test_scoped_negative_control_is_allowed(self):
        self.assertTrue(self.gate()["success"])

    def test_same_diagnostic_in_other_test_fails(self):
        result = self.gate(messages=[("ordinary", "[vk-validation] [SYNC-HAZARD-WRITE-AFTER-WRITE] vkCmdFillBuffer(): bug")])
        self.assertFalse(result["success"])
        self.assertEqual(1, len(result["diagnostics"]))

    def test_unrelated_error_in_control_fails(self):
        result = self.gate(messages=[(verify.CONTROL[1], "[vk-validation] [VUID-unrelated] bug")])
        self.assertFalse(result["success"])

    def test_setup_and_teardown_diagnostics_fail(self):
        self.assertFalse(self.gate(suite_message="[vk-validation] [VUID-setup] bug")["success"])

    def test_documented_descriptor_blind_spot_remains_visible(self):
        result = self.gate(skips=[verify.KNOWN_SKIP])
        self.assertTrue(result["success"])
        self.assertEqual(1, len(result["known_gaps"]))

    def test_no_gpu_is_a_failure(self):
        self.assertFalse(self.gate(skips=[("me.cortex.voxy.vk.VkContextTest", "device")])["success"])

    def test_missing_reports_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(verify.junit_result(Path(directory))["success"])

    def test_partial_report_fails(self):
        self.assertFalse(self.gate(malformed=True)["success"])


if __name__ == "__main__":
    unittest.main()
