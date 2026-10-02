import copy
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys

import baseline


class TestEvidence(unittest.TestCase):
    def test_reported_cases_include_parameters_skips_failures_and_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'TEST-integration.xml').write_text('''<testsuite tests="5">
                <testcase classname="Fixture" name="roundTrip"/>
                <testcase classname="Fixture" name="units[mmol]"/>
                <testcase classname="Fixture" name="watch"><skipped/></testcase>
                <testcase classname="Fixture" name="restore"><failure message="data lost"/></testcase>
                <testcase classname="Fixture" name="reconnect"><error message="timeout"/></testcase>
            </testsuite>''')
            result = baseline.test_results(path)
        self.assertEqual(dict(reported=5, executed=4, passed=2, failed=2, skipped=1), result['counts'])
        self.assertEqual(['restore', 'reconnect'], [failure['name'] for failure in result['failures']])

    def test_missing_or_malformed_xml_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.assertEqual(0, baseline.test_results(path)['xml_files'])
            (path / 'TEST-broken.xml').write_text('<testsuite>')
            result = baseline.test_results(path)
        self.assertEqual(0, result['counts']['passed'])
        self.assertEqual(1, len(result['errors']))

    def test_failed_command_preserves_exit_code_and_output(self):
        with tempfile.TemporaryDirectory() as directory:
            recorder = baseline.Recorder(Path(directory))
            code, text = recorder.run('failure', [sys.executable, '-c', 'print("partial evidence"); raise SystemExit(3)'])
            self.assertEqual(3, code)
            self.assertIn('partial evidence', text)
            self.assertEqual(3, recorder.commands[0]['exit_code'])
            self.assertEqual(text, (Path(directory) / 'failure.log').read_text())

    def test_cli_rejects_reused_evidence_directory_before_running_builds(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(baseline.ROOT / 'tools/validation/baseline.py'),
                                     '--output', directory], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertIn('NEW output directory', result.stderr)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_task_status_is_exact_not_a_prefix_match(self):
        log = '> Task :app:testFullDebugUnitTestCoverage\n> Task :app:testFullDebugUnitTest FAILED\n'
        self.assertEqual('FAILED', baseline.task_outcome(log, ':app:testFullDebugUnitTest'))
        self.assertEqual('NOT_OBSERVED', baseline.task_outcome(log, ':wear:testFullDebugUnitTest'))
        self.assertEqual('EXECUTED', baseline.task_outcome(log, ':app:testFullDebugUnitTestCoverage'))

    def test_manifest_default_debuggable_and_profileable_are_explicit(self):
        identity = baseline.manifest_identity('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="info.nightscout.androidaps" android:versionCode="1500"><application>
            <profileable android:shell="true"/></application></manifest>''')
        self.assertEqual('info.nightscout.androidaps', identity['application_id'])
        self.assertFalse(identity['debuggable'])
        self.assertTrue(identity['profileable'])

    def test_multiple_verified_signers_are_retained_without_source_stamp_confusion(self):
        certificates = ('a' * 64, 'b' * 64)
        text = '\n'.join(f'Signer #{i} certificate SHA-256 digest: {digest}' for i, digest in enumerate(certificates, 1))
        text += '\nSource Stamp Signer certificate SHA-256 digest: ' + 'c' * 64
        self.assertEqual(list(certificates), baseline.signer_fingerprints(text))
        self.assertEqual([], baseline.signer_fingerprints('DOES NOT VERIFY'))

    def test_scheme_labelled_certificates_from_newer_build_tools(self):
        certificate = 'c1af47f88bc6b3caab7f7bd842949aeee035b2c7f0f21afb65b4e725e37c167a'
        text = ('V2 Signer: certificate SHA-256 digest: ' + certificate + '\n'
                'V3.1 Signer: certificate SHA-256 digest: ' + certificate.upper() + '\n'
                'V2 Signer: public key SHA-256 digest: ' + 'a' * 64 + '\n'
                'Source Stamp Signer: certificate SHA-256 digest: ' + 'b' * 64)
        self.assertEqual([certificate], baseline.signer_fingerprints(text))

    def successful_build(self):
        apks = []
        for module, variant, debuggable in [('app', 'debug', True), ('app', 'loop', False),
                                           ('wear', 'debug', True), ('wear', 'loop', False), ('benchmark', 'loop', True)]:
            apks.append(dict(module=module, variant=variant, signers=['a' * 64],
                             identity=dict(application_id='info.nightscout.androidaps' if module != 'benchmark' else 'app.aaps.benchmark',
                                           debuggable=debuggable, profileable=True)))
        return dict(commands=[dict(name='build-tests', exit_code=0)], apks=apks,
                    tests=[dict(task=':core:data:test', outcome='EXECUTED', xml_files=1, errors=[],
                                counts=dict(executed=2, failed=0))])

    def test_successful_build_is_only_build_evidence(self):
        self.assertEqual([], baseline.evidence_errors(self.successful_build()))

    def test_successful_commands_cannot_hide_missing_test_discovery(self):
        report = self.successful_build()
        report['tests'] = []
        self.assertIn('No executed unit tests reported', baseline.evidence_errors(report))

    def test_empty_modules_are_distinguished_from_missing_discovery_of_compiled_classes(self):
        report = self.successful_build()
        report['tests'].append(dict(task=':empty:test', outcome='EXECUTED', xml_files=0, errors=[],
                                   compiled_class_files=0, counts=dict(executed=0, failed=0)))
        self.assertEqual([], baseline.evidence_errors(report))
        report['tests'][-1]['compiled_class_files'] = 1
        self.assertIn(':empty:test: missing test XML', baseline.evidence_errors(report))

    def test_failed_build_and_partial_artifacts_remain_failures(self):
        report = self.successful_build()
        report['commands'][0]['exit_code'] = 1
        report['apks'].pop(0)
        self.assertEqual(['Command failed: build-tests', 'app/full/debug: expected exactly one APK'], baseline.evidence_errors(report))

    def test_cached_skipped_unobserved_and_failed_tasks_are_not_fresh_execution(self):
        for status in ('FROM-CACHE', 'UP-TO-DATE', 'NOT_OBSERVED', 'FAILED', 'SKIPPED'):
            with self.subTest(status=status):
                report = self.successful_build()
                report['tests'][0]['outcome'] = status
                self.assertTrue(baseline.evidence_errors(report))

    def test_artifact_misidentification_and_signer_mismatch_are_blockers(self):
        original = self.successful_build()
        cases = [
            ('application_id', 'wrong.package', 'unexpected application ID'),
            ('debuggable', True, 'unexpected debuggability'),
            ('profileable', False, 'not shell-profileable'),
        ]
        for key, value, expected in cases:
            with self.subTest(key=key):
                report = copy.deepcopy(original)
                report['apks'][1]['identity'][key] = value
                self.assertTrue(any(expected in error for error in baseline.evidence_errors(report)))
        original['apks'][3]['signers'] = ['b' * 64]
        self.assertIn('Phone/watch Debug/Loop signers do not match', baseline.evidence_errors(original))


if __name__ == '__main__':
    unittest.main()
